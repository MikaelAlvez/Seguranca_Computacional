package q2_descoberta;

import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;
import comum.CanalSeguro;
import comum.SessaoSegura;
import comum.Validacao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * QUESTÃO 2 — Servidor de DIRETÓRIO (descoberta de serviços), TCP + criptografia híbrida.
 *
 * Protocolo (campos separados por '|'):
 *   REGISTER|nome|host|porta|servico1;servico2   -> OK|REGISTRADO     (exige credencial de servidor)
 *   DISCOVER|servico                             -> OK|nome,host,porta;nome,host,porta  | ERRO|msg
 *
 * Um servidor está ATIVO enquanto renovar o registro (heartbeat) dentro do prazo (lease).
 * Balanceamento: a cada DISCOVER a lista devolvida é rotacionada (round robin, contador global) ou
 * embaralhada (random); o cliente usa o primeiro da lista, então as chamadas se distribuem entre os servidores.
 */
public class ServidorDiretorio {
    static final long LEASE_MS = 15_000;

    private record Registro(String nome, String host, int porta, Set<String> servicos, long ultimaRenovacao) {}

    private final Log log = new Log("diretorio");
    private final KeyPair par = CanalSeguro.gerarParRSA();
    private final Map<String, Registro> registros = new ConcurrentHashMap<>();
    private final Set<String> catalogo = ConcurrentHashMap.newKeySet();           // todos os serviços já registrados
    private final AtomicInteger contadorRR = new AtomicInteger();   // round robin GLOBAL: alterna entre as chamadas, qualquer que seja o serviço
    private final String politica = System.getProperty("politica", "round-robin"); // ou "random"

    public void executar(int porta) throws IOException {
        try (ServerSocket servidor = new ServerSocket(porta)) {
            log.info("Diretório pronto na porta " + porta + " (política de balanceamento: " + politica + ")");
            int n = 0;
            while (true) {
                Socket s = servidor.accept();
                new Thread(() -> atender(s), "conexao-" + (++n)).start();
            }
        }
    }

    private void atender(Socket s) {
        try (CanalTCP canal = CanalTCP.aceitar(s, par, 5000, log, null)) {
            String msg;
            while ((msg = canal.receber()) != null) {
                canal.enviar(processar(canal.sessao(), msg));
            }
        } catch (ErroIntegridadeException e) {
            log.seguranca("Conexão de " + s.getRemoteSocketAddress() + " encerrada: " + e.getMessage());
        } catch (IOException e) {
            log.info("Conexão de " + s.getRemoteSocketAddress() + " finalizada (" + e.getMessage() + ")");
        }
    }

    private String processar(SessaoSegura sessao, String msg) {
        String[] p = msg.split("\\|", -1);
        return switch (p[0]) {
            case "REGISTER" -> registrar(sessao, p);
            case "DISCOVER" -> descobrir(p);
            default -> erroEntrada("comando desconhecido");
        };
    }

    private String registrar(SessaoSegura sessao, String[] p) {
        if (!("SERVIDOR:" + Config.TOKEN_SERVIDOR_CALC).equals(sessao.credencial())) {
            log.seguranca("REGISTER NEGADO: sessão " + sessao.sid() + " sem credencial de servidor de serviço");
            return "ERRO|NAO_AUTORIZADO";
        }
        try {
            if (p.length != 5 || !Validacao.nomeDnsValido(p[1]) || p[2].isBlank()) return erroEntrada("REGISTER malformado");
            int porta = Integer.parseInt(p[3]);
            Set<String> servicos = new HashSet<>();
            for (String sv : p[4].split(";")) {
                String norm = Validacao.normalizar(sv);
                if (!norm.isEmpty()) servicos.add(norm);
            }
            if (porta < 1 || porta > 65535 || servicos.isEmpty()) return erroEntrada("porta ou serviços inválidos");
            boolean novo = !registros.containsKey(p[1]);
            registros.put(p[1], new Registro(p[1], p[2], porta, servicos, System.currentTimeMillis()));
            catalogo.addAll(servicos);
            if (novo) {
                log.info("REGISTRADO: " + p[1] + " em " + p[2] + ":" + porta + " oferecendo " + servicos
                        + " | servidores no diretório: " + new java.util.TreeSet<>(registros.keySet()));
            }
            return "OK|REGISTRADO";
        } catch (NumberFormatException e) {
            return erroEntrada("porta não numérica");
        }
    }

    private String descobrir(String[] p) {
        if (p.length != 2) return erroEntrada("DISCOVER malformado");
        String servico = Validacao.normalizar(p[1]);
        if (servico.isEmpty() || !catalogo.contains(servico)) {
            return erroEntrada("serviço não cadastrado: '" + p[1] + "'");
        }
        long agora = System.currentTimeMillis();
        List<Registro> ativos = registros.values().stream()
                .filter(r -> agora - r.ultimaRenovacao() <= LEASE_MS && r.servicos().contains(servico))
                .sorted(Comparator.comparing(Registro::nome))
                .collect(Collectors.toCollection(ArrayList::new));
        if (ativos.isEmpty()) {
            log.info("DISCOVER '" + servico + "': nenhum servidor ativo");
            return "OK|";
        }
        if (politica.equals("random")) {
            Collections.shuffle(ativos);
        } else {
            int inicio = contadorRR.getAndIncrement() % ativos.size();
            Collections.rotate(ativos, -inicio);          // round robin: o primeiro da lista alterna a cada consulta
        }
        String lista = ativos.stream().map(r -> r.nome() + "," + r.host() + "," + r.porta())
                .collect(Collectors.joining(";"));
        log.info("DISCOVER '" + servico + "' -> ordem [" + ativos.stream().map(Registro::nome)
                .collect(Collectors.joining(", ")) + "] (" + politica + ")");
        return "OK|" + lista;
    }

    private String erroEntrada(String motivo) {
        log.erro(Config.MSG_ERRO_ENTRADA + " (" + motivo + ")");
        return "ERRO|" + Config.MSG_ERRO_ENTRADA;
    }

    public static void main(String[] args) {
        try {
            new ServidorDiretorio().executar(args.length > 0 ? Integer.parseInt(args[0]) : Config.DIRETORIO_PORTA);
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (porta inválida)");
        } catch (IOException e) {
            System.out.println("Não foi possível iniciar o diretório: " + e.getMessage());
        }
    }
}
