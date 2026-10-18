package q2_descoberta;

import comum.Adulteracao;
import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * QUESTÃO 2 — Teste automatizado com informações VÁLIDAS e INVÁLIDAS (nível de protocolo).
 *
 * Pré-requisito: Diretório e S1, S2, S3 já rodando (processos separados).
 * Conversa com o Diretório (7000) e com o S1 (7101) pelo canal seguro real e compara esperado x obtido.
 * Código de saída 0 somente se todas as verificações passarem.
 *
 * Uso: java q2_descoberta.TesteQ2
 */
public class TesteQ2 {
    private static final String ERRO_ENTRADA = "ERRO|" + Config.MSG_ERRO_ENTRADA;
    private static final String ENCERRADA = "<conexão encerrada>";
    private static final Log MUDO = Log.mudo();
    private static final String CRED_SERVIDOR = "SERVIDOR:" + Config.TOKEN_SERVIDOR_CALC;
    private static final int PORTA_S1 = 7101;

    private static int total, passou;

    private static void verificar(String id, String entrada, String esperado, String obtido) {
        total++;
        boolean ok = esperado.equals(obtido);
        if (ok) passou++;
        System.out.printf("%-5s %-58s esperado=%-34s obtido=%-34s %s%n", id, cortar(entrada.replace(" # ", " "), 58),
                cortar(esperado, 34), cortar(obtido, 34), ok ? "OK" : "<<< FALHOU");
    }

    private static String cortar(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    private static CanalTCP abrir(int porta, String credencial) throws Exception {
        return CanalTCP.conectar(Config.HOST, porta, credencial, false, 3000, MUDO);
    }

    private static String ler(CanalTCP c) {
        try {
            String r = c.receber();
            return r == null ? ENCERRADA : r;
        } catch (ErroIntegridadeException e) {
            return "<resposta com integridade inválida>";
        } catch (IOException e) {
            return ENCERRADA;
        }
    }

    private static String pedir(CanalTCP c, String msg) throws IOException {
        c.enviar(msg);
        return ler(c);
    }

    /** "OK|S1,127.0.0.1,7101;S2,..." -> "OK[S1,S2,S3]" (nomes ordenados); outras respostas ficam como vieram. */
    private static String nomes(String resposta) {
        if (!resposta.startsWith("OK|") || resposta.equals("OK|REGISTRADO")) return resposta;
        TreeSet<String> n = new TreeSet<>();
        String corpo = resposta.substring(3);
        if (!corpo.isEmpty()) for (String item : corpo.split(";")) n.add(item.split(",")[0]);
        return "OK" + (n.isEmpty() ? "|" : n.toString().replace(" ", ""));
    }

    private static String primeiro(String resposta) {
        String corpo = resposta.substring(3);
        return corpo.split(";")[0].split(",")[0];
    }

    private static String handshakeRecusado(int porta) {
        try {
            CanalTCP c = CanalTCP.conectar(Config.HOST, porta, "", true, 3000, MUDO);
            c.close();
            return "handshake concluído";
        } catch (IOException | ErroIntegridadeException e) {
            return "<handshake recusado>";
        }
    }

    private static String lixoNoHandshake(int porta) throws IOException {
        try (Socket s = new Socket(Config.HOST, porta)) {
            s.setSoTimeout(3000);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out.println("isto-nao-e-um-handshake");
            return in.readLine() == null ? ENCERRADA : "<respondeu>";
        } catch (IOException e) {
            return ENCERRADA;
        }
    }

    public static void main(String[] args) throws Exception {
        try (CanalTCP dir = abrir(Config.DIRETORIO_PORTA, ""); CanalTCP calc = abrir(PORTA_S1, "");
             CanalTCP servidor = abrir(Config.DIRETORIO_PORTA, CRED_SERVIDOR)) {

            System.out.println("\n=== A) DESCOBERTA VÁLIDA (diretório) ===");
            String todos = "OK[S1,S2,S3]";
            verificar("V01", "DISCOVER|Calcular soma", todos, nomes(pedir(dir, "DISCOVER|Calcular soma")));
            verificar("V02", "DISCOVER|Calcular subtração", todos, nomes(pedir(dir, "DISCOVER|Calcular subtração")));
            verificar("V03", "DISCOVER|Calcular multiplicação", todos, nomes(pedir(dir, "DISCOVER|Calcular multiplicação")));
            verificar("V04", "DISCOVER|Calcular divisão", todos, nomes(pedir(dir, "DISCOVER|Calcular divisão")));
            verificar("V05", "DISCOVER|calcular SOMA (maiúsculas/minúsculas)", todos, nomes(pedir(dir, "DISCOVER|calcular SOMA")));
            verificar("V06", "DISCOVER|'  Calcular   soma  ' (espaços extras)", todos, nomes(pedir(dir, "DISCOVER|  Calcular   soma  ")));
            verificar("V07", "DISCOVER|Calcular subtracao (sem acento)", todos, nomes(pedir(dir, "DISCOVER|Calcular subtracao")));

            System.out.println("\n=== B) ROUND ROBIN (6 consultas seguidas) ===");
            List<String> primeiros = new ArrayList<>();
            for (int i = 0; i < 6; i++) primeiros.add(primeiro(pedir(dir, "DISCOVER|Calcular soma")));
            Map<String, Integer> cont = new HashMap<>();
            for (String p : primeiros) cont.merge(p, 1, Integer::sum);
            System.out.println("      primeiro da lista em cada consulta: " + primeiros);
            verificar("V08", "cada servidor aparece em 1º lugar 2 vezes em 6", "{S1=2, S2=2, S3=2}", new java.util.TreeMap<>(cont).toString());
            boolean ciclico = primeiros.get(0).equals(primeiros.get(3)) && primeiros.get(1).equals(primeiros.get(4))
                    && primeiros.get(2).equals(primeiros.get(5)) && !primeiros.get(0).equals(primeiros.get(1));
            verificar("V09", "a ordem se repete de 3 em 3 (alternância cíclica)", "true", String.valueOf(ciclico));

            System.out.println("\n=== C) CÁLCULOS VÁLIDOS direto no S1 ===");
            String[][] ok = {
                    {"V10", "CALC|SOMA|10|20", "RESULT|30|S1"},
                    {"V11", "CALC|SUBTRACAO|7|2", "RESULT|5|S1"},
                    {"V12", "CALC|MULTIPLICACAO|5|4", "RESULT|20|S1"},
                    {"V13", "CALC|DIVISAO|9|4", "RESULT|2.25|S1"},
                    {"V14", "CALC|DIVISAO|10|2", "RESULT|5|S1"},
                    {"V15", "CALC|DIVISAO|1|3", "RESULT|0.3333333333|S1"},
                    {"V16", "CALC|SOMA|7.5|2.25 # (decimais)", "RESULT|9.75|S1"},
                    {"V17", "CALC|MULTIPLICACAO|-3|4 # (negativo)", "RESULT|-12|S1"},
                    {"V18", "CALC|SOMA|0.1|0.2 # (sem erro de ponto flutuante)", "RESULT|0.3|S1"},
                    {"V19", "CALC|SOMA|99999999999999999999|1 # (número enorme)", "RESULT|100000000000000000000|S1"},
                    {"V20", "CALC|SUBTRACAO|5|5 # (resultado zero)", "RESULT|0|S1"},
            };
            for (String[] t : ok) verificar(t[0], t[1], t[2], pedir(calc, t[1].split(" # ")[0]));

            System.out.println("\n=== D) CÁLCULOS INVÁLIDOS no S1 ===");
            String[][] ruins = {
                    {"I01", "CALC|DIVISAO|10|0", "ERRO|Divisão por zero não é permitida"},
                    {"I02", "CALC|SOMA|abc|1 # (operando não numérico)", ERRO_ENTRADA},
                    {"I03", "CALC|SOMA|1| # (operando vazio)", ERRO_ENTRADA},
                    {"I04", "CALC|SOMA|1;2|3 # (estilo injeção)", ERRO_ENTRADA},
                    {"I05", "CALC|MODULO|10|3 # (operação inexistente)", ERRO_ENTRADA},
                    {"I06", "CALC|SOMA|1 # (falta operando)", ERRO_ENTRADA},
                    {"I07", "CALC|SOMA|1|2|3 # (campo a mais)", ERRO_ENTRADA},
                    {"I08", "FOO # (comando desconhecido)", ERRO_ENTRADA},
                    {"I09", " # (mensagem vazia)", ERRO_ENTRADA},
            };
            for (String[] t : ruins) verificar(t[0], t[1], t[2], pedir(calc, t[1].split(" # ")[0]));
            verificar("I10", "S1 segue funcionando após os erros: CALC|SOMA|1|1", "RESULT|2|S1", pedir(calc, "CALC|SOMA|1|1"));

            System.out.println("\n=== E) DESCOBERTA INVÁLIDA ===");
            verificar("I11", "DISCOVER|Calcular modulo (serviço inexistente)", ERRO_ENTRADA, pedir(dir, "DISCOVER|Calcular modulo"));
            verificar("I12", "DISCOVER| (serviço vazio)", ERRO_ENTRADA, pedir(dir, "DISCOVER|"));
            verificar("I13", "DISCOVER (sem campo de serviço)", ERRO_ENTRADA, pedir(dir, "DISCOVER"));
            verificar("I14", "DISCOVER|a|b (campo a mais)", ERRO_ENTRADA, pedir(dir, "DISCOVER|a|b"));
            verificar("I15", "DISCOVER|Calcular soma; DROP TABLE (injeção)", ERRO_ENTRADA, pedir(dir, "DISCOVER|Calcular soma; DROP TABLE"));
            verificar("I16", "PING (comando desconhecido)", ERRO_ENTRADA, pedir(dir, "PING"));
            verificar("I17", " (mensagem vazia)", ERRO_ENTRADA, pedir(dir, ""));

            System.out.println("\n=== F) REGISTRO INVÁLIDO / NÃO AUTORIZADO (o diretório NÃO pode mudar) ===");
            String reg = "REGISTER|S9|127.0.0.1|7109|Calcular potencia";
            try (CanalTCP intruso = abrir(Config.DIRETORIO_PORTA, ""); CanalTCP errado = abrir(Config.DIRETORIO_PORTA, "SERVIDOR:token-invalido")) {
                verificar("I18", "REGISTER por cliente comum (sem credencial)", "ERRO|NAO_AUTORIZADO", pedir(intruso, reg));
                verificar("I19", "REGISTER com token errado", "ERRO|NAO_AUTORIZADO", pedir(errado, reg));
            }
            String[][] regRuins = {
                    {"I20", "REGISTER|S9|127.0.0.1|7109 # (falta o campo de serviços)"},
                    {"I21", "REGISTER|S 9|127.0.0.1|7109|Calcular potencia # (nome inválido)"},
                    {"I22", "REGISTER|S9||7109|Calcular potencia # (host vazio)"},
                    {"I23", "REGISTER|S9|127.0.0.1|abc|Calcular potencia # (porta não numérica)"},
                    {"I24", "REGISTER|S9|127.0.0.1|0|Calcular potencia # (porta 0)"},
                    {"I25", "REGISTER|S9|127.0.0.1|70000|Calcular potencia # (porta > 65535)"},
                    {"I26", "REGISTER|S9|127.0.0.1|7109| # (lista de serviços vazia)"},
                    {"I27", "REGISTER|S9|127.0.0.1|7109|;; # (serviços só com separadores)"},
            };
            for (String[] t : regRuins) verificar(t[0], t[1], ERRO_ENTRADA, pedir(servidor, t[1].split(" # ")[0]));
            verificar("I28", "diretório inalterado: DISCOVER|Calcular soma", todos, nomes(pedir(dir, "DISCOVER|Calcular soma")));
            verificar("I29", "'Calcular potencia' continua inexistente", ERRO_ENTRADA, pedir(dir, "DISCOVER|Calcular potencia"));

            System.out.println("\n=== G) REGISTRO VÁLIDO e SERVIDOR ATIVO x INATIVO (lease de 15 s) ===");
            verificar("V21", "REGISTER|S9|...|Calcular potencia (autorizado)", "OK|REGISTRADO", pedir(servidor, reg));
            verificar("V22", "DISCOVER|Calcular potencia (S9 ativo)", "OK[S9]", nomes(pedir(dir, "DISCOVER|Calcular potencia")));
            System.out.println("      aguardando 16 s: S9 não renova o registro; S1, S2 e S3 renovam a cada 5 s...");
            Thread.sleep(16_000);
            verificar("V23", "DISCOVER|Calcular potencia (S9 inativo)", "OK|", nomes(pedir(dir, "DISCOVER|Calcular potencia")));
            verificar("V24", "S1, S2, S3 seguem ativos (heartbeat)", todos, nomes(pedir(dir, "DISCOVER|Calcular soma")));

            System.out.println("\n=== H) FALHAS DE INTEGRIDADE / CIFRA / HANDSHAKE (a conexão deve ser ENCERRADA) ===");
            Adulteracao[] modos = {Adulteracao.MAC_CORROMPIDO, Adulteracao.CIFRADO_ADULTERADO, Adulteracao.HASH_SEM_CHAVE};
            String[] rotulos = {"HMAC corrompido", "texto cifrado adulterado", "SHA-256 sem chave no lugar do HMAC"};
            for (int i = 0; i < modos.length; i++) {
                try (CanalTCP c = abrir(Config.DIRETORIO_PORTA, "")) {
                    c.enviarAdulterado("DISCOVER|Calcular soma", modos[i]);
                    verificar("S0" + (i + 1), "diretório: " + rotulos[i], ENCERRADA, ler(c));
                }
            }
            try (CanalTCP c = abrir(PORTA_S1, "")) {
                c.enviarAdulterado("CALC|SOMA|1|1", Adulteracao.MAC_CORROMPIDO);
                verificar("S04", "S1: HMAC corrompido", ENCERRADA, ler(c));
            }
            try (CanalTCP c = abrir(Config.DIRETORIO_PORTA, "")) {
                c.enviarDuplicado("DISCOVER|Calcular soma");
                verificar("S05", "replay: 1º pacote é aceito", todos, nomes(ler(c)));
                verificar("S06", "replay: 2º pacote idêntico encerra a conexão", ENCERRADA, ler(c));
            }
            verificar("S07", "diretório: handshake com cifra RSA corrompida", "<handshake recusado>", handshakeRecusado(Config.DIRETORIO_PORTA));
            verificar("S08", "S1: handshake com cifra RSA corrompida", "<handshake recusado>", handshakeRecusado(PORTA_S1));
            verificar("S09", "diretório: lixo no lugar do handshake", ENCERRADA, lixoNoHandshake(Config.DIRETORIO_PORTA));
            verificar("S10", "S1: lixo no lugar do handshake", ENCERRADA, lixoNoHandshake(PORTA_S1));
            verificar("S11", "diretório segue funcionando após os ataques", todos, nomes(pedir(dir, "DISCOVER|Calcular soma")));
            verificar("S12", "S1 segue funcionando após os ataques", "RESULT|3|S1", pedir(calc, "CALC|SOMA|1|2"));
        }
        System.out.printf("%n=== RESULTADO: %d/%d verificações passaram ===%n", passou, total);
        System.exit(passou == total ? 0 : 1);
    }
}
