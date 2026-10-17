package q1_dns;

import comum.Config;
import comum.SessaoSegura;
import comum.ServidorUDPSeguro;
import comum.Validacao;

import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * QUESTÃO 1 — Mini-DNS (UDP) com criptografia híbrida.
 *
 * Comandos (já autenticados e decifrados pela classe-base):
 *   QUERY <nome>            -> ANSWER <nome> <ip>   | ERRO <msg>
 *   SUBSCRIBE               -> OK ASSINADO          (passa a receber NOTIFY)
 *   UPDATE <nome> <ip>      -> OK UPDATED ...       | ERRO ...   (só sessão com credencial de REGISTRADOR)
 *
 * Coleções thread-safe (várias threads tratam datagramas ao mesmo tempo):
 *   - mapa de nomes:  ConcurrentHashMap  (leituras e escritas concorrentes sem bloqueio global)
 *   - assinantes:     CopyOnWriteArrayList (muitas leituras, poucas escritas)
 *   - fila de avisos: LinkedBlockingQueue (produtor = UPDATE, consumidor = thread notificadora)
 */
public final class ServidorDNS extends ServidorUDPSeguro {
    private final Map<String, String> mapa = new ConcurrentHashMap<>();
    private final List<SessaoSegura> assinantes = new CopyOnWriteArrayList<>();
    private final BlockingQueue<String[]> filaNotificacoes = new LinkedBlockingQueue<>();

    public ServidorDNS(int porta) throws SocketException {
        super("dns-servidor", porta);
        for (int i = 1; i <= 10; i++) {
            mapa.put("servidor" + i, "192.168.0." + (i * 10));   // servidor1 -> .10 ... servidor10 -> .100
        }
        log.info("Mapa inicial carregado com " + mapa.size() + " nomes (servidor1 -> 192.168.0.10 ... servidor10 -> 192.168.0.100)");
        iniciarNotificador();
    }

    @Override
    protected void tratarMensagem(SessaoSegura sessao, String mensagem) {
        String[] p = mensagem.trim().split("\\s+");
        switch (p[0]) {
            case "QUERY" -> consultar(sessao, p);
            case "SUBSCRIBE" -> {
                if (!assinantes.contains(sessao)) assinantes.add(sessao);
                log.info("Sessão " + sessao.sid() + " assinou notificações (assinantes: " + assinantes.size() + ")");
                responder(sessao, "OK ASSINADO");
            }
            case "UPDATE" -> atualizar(sessao, p);
            default -> rejeitarEntrada(sessao, "comando desconhecido");
        }
    }

    private void consultar(SessaoSegura sessao, String[] p) {
        if (p.length != 2 || !Validacao.nomeDnsValido(p[1])) {
            rejeitarEntrada(sessao, "formato de consulta inválido");
            return;
        }
        String ip = mapa.get(p[1]);
        if (ip == null) {
            rejeitarEntrada(sessao, "nome inexistente: " + p[1]);
            return;
        }
        log.info("Resolução: " + p[1] + " -> " + ip);
        responder(sessao, "ANSWER " + p[1] + " " + ip);
    }

    private void atualizar(SessaoSegura sessao, String[] p) {
        // Autorização: só quem apresentou a credencial de registrador no handshake (cifrada com RSA)
        if (!("REGISTRADOR:" + Config.TOKEN_REGISTRADOR).equals(sessao.credencial())) {
            log.seguranca("UPDATE NEGADO: sessão " + sessao.sid() + " não possui credencial de registrador");
            responder(sessao, "ERRO NAO_AUTORIZADO");
            return;
        }
        if (p.length != 3 || !Validacao.nomeDnsValido(p[1]) || !Validacao.ipv4Valido(p[2])) {
            rejeitarEntrada(sessao, "UPDATE com nome/IP inválido");
            return;
        }
        String anterior = mapa.put(p[1], p[2]);       // binding dinâmico
        log.info("BINDING DINÂMICO: " + p[1] + " " + (anterior == null ? "(novo)" : anterior) + " -> " + p[2]
                + " (HMAC do registrador validado)");
        responder(sessao, "OK UPDATED " + p[1] + " " + p[2]);
        filaNotificacoes.offer(new String[]{p[1], p[2]});   // a thread notificadora avisa os assinantes
    }

    private void rejeitarEntrada(SessaoSegura sessao, String motivo) {
        log.erro(Config.MSG_ERRO_ENTRADA + " (" + motivo + ")");
        responder(sessao, "ERRO " + Config.MSG_ERRO_ENTRADA);
    }

    /** Thread consumidora: espera atualizações na fila e notifica cada assinante com um pacote cifrado próprio. */
    private void iniciarNotificador() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String[] atualizacao = filaNotificacoes.take();
                    log.info("Notificando " + assinantes.size() + " assinante(s): " + atualizacao[0] + " -> " + atualizacao[1]);
                    for (SessaoSegura assinante : assinantes) {
                        responder(assinante, "NOTIFY " + atualizacao[0] + " " + atualizacao[1]);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "notificador");
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) {
        try {
            new ServidorDNS(args.length > 0 ? Integer.parseInt(args[0]) : Config.DNS_PORTA).executar();
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (porta inválida)");
        } catch (Exception e) {
            System.out.println("Não foi possível iniciar o servidor DNS: " + e.getMessage());
        }
    }
}
