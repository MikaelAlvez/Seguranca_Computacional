package q2_descoberta;

import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QUESTÃO 2 — Cliente: descobre servidores no Diretório e chama o serviço escolhido.
 *
 * Uso: java q2_descoberta.ClienteCalc <id> [--descobrir "Calcular soma"]... [--sem-failover] [--calc "10 + 20"]... [--interativo]
 *   operadores: +  -  *  /  %   (o % -> "Calcular modulo", serviço que NÃO existe: caso de erro)
 *
 * Cada chamada abre uma sessão cifrada NOVA (handshake RSA -> AES+HMAC) com o servidor escolhido.
 * Se o servidor falhar (indisponível ou HMAC inválido), o cliente o REJEITA e tenta o próximo da lista.
 */
public class ClienteCalc {
    private record Endereco(String nome, String host, int porta) {}

    private static final Pattern EXPRESSAO =
            Pattern.compile("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*([+\\-*/%])\\s*(-?\\d+(?:\\.\\d+)?)\\s*$");
    private static final Map<String, String> SERVICO_POR_OP = Map.of(
            "+", "Calcular soma", "-", "Calcular subtração", "*", "Calcular multiplicação",
            "/", "Calcular divisão", "%", "Calcular modulo");
    private static final Map<String, String> CODIGO_POR_OP = Map.of(
            "+", "SOMA", "-", "SUBTRACAO", "*", "MULTIPLICACAO", "/", "DIVISAO", "%", "MODULO");

    private final Log log;
    private boolean semFailover;       // true: se o servidor escolhido falhar, REJEITA a execução (não tenta outro)

    public ClienteCalc(String id) { this.log = new Log("calc-cliente-" + id); }

    /** Consulta o diretório; devolve a lista de servidores (pode ser vazia) ou null em caso de erro. */
    List<Endereco> descobrir(String servico) {
        try (CanalTCP canal = CanalTCP.conectar(Config.HOST, Config.DIRETORIO_PORTA, "", false, 4000, log)) {
            canal.enviar("DISCOVER|" + servico);
            String resp = canal.receber();
            if (resp == null) { log.erro("Diretório encerrou a conexão"); return null; }
            if (resp.startsWith("ERRO|")) {
                log.erro(resp.substring(5));
                return null;
            }
            List<Endereco> lista = new ArrayList<>();
            String corpo = resp.substring(3);
            if (!corpo.isEmpty()) {
                for (String item : corpo.split(";")) {
                    String[] e = item.split(",");
                    lista.add(new Endereco(e[0], e[1], Integer.parseInt(e[2])));
                }
            }
            log.info("DESCOBERTA '" + servico + "': " + (lista.isEmpty() ? "nenhum servidor ativo"
                    : lista.stream().map(e -> e.nome() + "@" + e.host() + ":" + e.porta()).toList())
                    + " (lista autenticada pelo HMAC do diretório)");
            return lista;
        } catch (ErroIntegridadeException e) {
            log.seguranca("Resposta do diretório REJEITADA (integridade): " + e.getMessage());
        } catch (IOException e) {
            log.erro("Diretório indisponível: " + e.getMessage());
        }
        return null;
    }

    void calcular(String expressao) {
        Matcher m = EXPRESSAO.matcher(expressao);
        if (!m.matches()) {
            log.erro(Config.MSG_ERRO_ENTRADA + " (expressão inválida: '" + expressao + "')");
            return;
        }
        String op = m.group(2);
        List<Endereco> candidatos = descobrir(SERVICO_POR_OP.get(op));
        if (candidatos == null) return;                       // erro já informado
        if (candidatos.isEmpty()) { log.erro("Nenhum servidor ativo oferece esse serviço."); return; }

        for (Endereco alvo : candidatos) {                    // failover: tenta o próximo se um falhar
            try (CanalTCP canal = CanalTCP.conectar(alvo.host(), alvo.porta(), "", false, 4000, log)) {
                canal.enviar("CALC|" + CODIGO_POR_OP.get(op) + "|" + m.group(1) + "|" + m.group(3));
                String r = canal.receber();
                if (r != null && r.startsWith("RESULT|")) {
                    String[] p = r.split("\\|");
                    log.info("RESULTADO: " + expressao.trim() + " = " + p[1] + "  (calculado por " + p[2]
                            + " em canal seguro AES+HMAC)");
                    return;
                }
                log.erro("Resposta do servidor " + alvo.nome() + ": " + (r == null ? "conexão encerrada" : r.replace("ERRO|", "")));
                return;
            } catch (ErroIntegridadeException e) {
                log.seguranca("Servidor " + alvo.nome() + " REJEITADO: falha de integridade/HMAC (" + e.getMessage()
                        + "). Execução cancelada nesse nó; os demais componentes seguem normais.");
                if (semFailover) { log.erro("Execução REJEITADA (--sem-failover: não tenta outro servidor)."); return; }
            } catch (IOException e) {
                log.erro("Servidor " + alvo.nome() + " indisponível (" + e.getMessage() + ").");
                if (semFailover) { log.erro("Execução REJEITADA (--sem-failover: não tenta outro servidor)."); return; }
            }
        }
        log.erro("Nenhum servidor disponível e íntegro para completar o cálculo.");
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java q2_descoberta.ClienteCalc <id> [--descobrir \"servico\"] [--sem-failover] [--calc \"10 + 20\"] [--interativo]");
            return;
        }
        ClienteCalc c = new ClienteCalc(args[0]);
        boolean interativo = false;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--descobrir" -> c.descobrir(args[++i]);
                    case "--calc" -> c.calcular(args[++i]);
                    case "--sem-failover" -> c.semFailover = true;      // vale para os --calc que vierem depois
                    case "--interativo" -> interativo = true;
                    default -> System.out.println(Config.MSG_ERRO_ENTRADA + " (opção desconhecida: " + args[i] + ")");
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (faltou o valor de uma opção)");
            return;
        }
        if (interativo) {
            Scanner teclado = new Scanner(System.in);
            System.out.println("Digite 'descobrir <serviço>', uma conta como '10 + 20', ou 'sair':");
            while (teclado.hasNextLine()) {
                String linha = teclado.nextLine().trim();
                if (linha.equalsIgnoreCase("sair")) break;
                if (linha.toLowerCase().startsWith("descobrir ")) c.descobrir(linha.substring(10).trim());
                else c.calcular(linha);
            }
        }
    }
}
