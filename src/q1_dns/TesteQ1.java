package q1_dns;

import comum.Adulteracao;
import comum.ClienteUDPSeguro;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * QUESTÃO 1 — Teste automatizado com informações VÁLIDAS e INVÁLIDAS.
 *
 * Fala com o ServidorDNS (processo separado) pelo protocolo cifrado real e compara cada resposta com a esperada.
 * Cobre entradas que os clientes normais não geram (comando desconhecido, replay, datagrama lixo, sessão falsa).
 * Saída: tabela ID | entrada | esperado | obtido | resultado. Código de saída 0 só se tudo passar.
 *
 * Uso: java q1_dns.TesteQ1     (com o ServidorDNS já rodando)
 */
public class TesteQ1 {
    private static final String ERRO_ENTRADA = "ERRO " + Config.MSG_ERRO_ENTRADA;
    private static final String SEM_RESPOSTA = "<sem resposta>";
    private static final InetSocketAddress DNS = new InetSocketAddress(Config.HOST, Config.DNS_PORTA);
    private static final Log MUDO = Log.mudo();

    private static int total, passou;

    private static void verificar(String id, String entrada, String esperado, String obtido) {
        total++;
        boolean ok = esperado.equals(obtido);
        if (ok) passou++;
        System.out.printf("%-5s %-46s esperado=%-44s obtido=%-44s %s%n", id, cortar(entrada, 46),
                cortar(esperado, 44), cortar(obtido, 44), ok ? "OK" : "<<< FALHOU");
    }

    private static String cortar(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    /** Envia e devolve a resposta decifrada (ou um marcador se não houver resposta/for inválida). */
    private static String pedir(ClienteUDPSeguro c, String msg) throws IOException {
        c.enviar(msg);
        return aguardar(c, 1500);
    }

    private static String aguardar(ClienteUDPSeguro c, int ms) throws IOException {
        try {
            return c.receber(ms);
        } catch (SocketTimeoutException e) {
            return SEM_RESPOSTA;
        } catch (ErroIntegridadeException e) {
            return "<resposta com integridade inválida>";
        }
    }

    private static ClienteUDPSeguro sessao(String credencial) throws Exception {
        ClienteUDPSeguro c = new ClienteUDPSeguro(DNS, 0, MUDO);
        c.handshake(credencial, false);
        return c;
    }

    private static String bruto(String texto) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] d = texto.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(d, d.length, InetAddress.getByName(Config.HOST), Config.DNS_PORTA));
            s.setSoTimeout(1500);
            DatagramPacket r = new DatagramPacket(new byte[8192], 8192);
            try { s.receive(r); return new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8); }
            catch (SocketTimeoutException e) { return SEM_RESPOSTA; }
        }
    }

    public static void main(String[] args) throws Exception {
        String credReg = "REGISTRADOR:" + Config.TOKEN_REGISTRADOR;
        try (ClienteUDPSeguro cli = sessao(""); ClienteUDPSeguro reg = sessao(credReg); ClienteUDPSeguro intruso = sessao("")) {

            System.out.println("\n=== A) CONSULTAS VÁLIDAS: os 10 nomes do enunciado ===");
            for (int i = 1; i <= 10; i++) {
                verificar("V" + String.format("%02d", i), "QUERY servidor" + i, "ANSWER servidor" + i + " 192.168.0." + (i * 10),
                        pedir(cli, "QUERY servidor" + i));
            }

            System.out.println("\n=== B) CONSULTAS INVÁLIDAS (nome inexistente / formato inválido / comando inválido) ===");
            verificar("I01", "QUERY servidor99 (inexistente)", ERRO_ENTRADA, pedir(cli, "QUERY servidor99"));
            verificar("I02", "QUERY servidor0 (inexistente)", ERRO_ENTRADA, pedir(cli, "QUERY servidor0"));
            verificar("I03", "QUERY (sem nome)", ERRO_ENTRADA, pedir(cli, "QUERY "));
            verificar("I04", "QUERY 'ser vidor' (espaço no nome)", ERRO_ENTRADA, pedir(cli, "QUERY ser vidor"));
            verificar("I05", "QUERY 'servidor!' (caractere especial)", ERRO_ENTRADA, pedir(cli, "QUERY servidor!"));
            verificar("I06", "QUERY '.servidor1' (começa com ponto)", ERRO_ENTRADA, pedir(cli, "QUERY .servidor1"));
            verificar("I07", "QUERY nome com 64 caracteres (longo demais)", ERRO_ENTRADA, pedir(cli, "QUERY " + "a".repeat(64)));
            verificar("I08", "QUERY 'servidór1' (acento)", ERRO_ENTRADA, pedir(cli, "QUERY servidór1"));
            verificar("I09", "QUERY 'servidor1;DROP' (estilo injeção)", ERRO_ENTRADA, pedir(cli, "QUERY servidor1;DROP"));
            verificar("I10", "QUERY SERVIDOR1 (maiúsculas: nomes são case-sensitive)", ERRO_ENTRADA, pedir(cli, "QUERY SERVIDOR1"));
            verificar("I11", "QUERY servidor1 servidor2 (nomes demais)", ERRO_ENTRADA, pedir(cli, "QUERY servidor1 servidor2"));
            verificar("I12", "PING (comando desconhecido)", ERRO_ENTRADA, pedir(cli, "PING"));
            verificar("I13", "'' (mensagem vazia)", ERRO_ENTRADA, pedir(cli, ""));

            System.out.println("\n=== C) UPDATE INVÁLIDO como REGISTRADOR autorizado (o mapa NÃO pode mudar) ===");
            String[][] invalidos = {
                    {"I14", "UPDATE servidor2 999.1.1.1", "IP com octeto > 255"},
                    {"I15", "UPDATE servidor2 192.168.0.256", "IP com octeto 256"},
                    {"I16", "UPDATE servidor2 192.168.0", "IP com 3 octetos"},
                    {"I17", "UPDATE servidor2 192.168.0.1.5", "IP com 5 octetos"},
                    {"I18", "UPDATE servidor2 abc", "IP não numérico"},
                    {"I19", "UPDATE servidor2 192.168.00.1", "IP com zero à esquerda"},
                    {"I20", "UPDATE ser vidor 1.1.1.1", "nome com espaço"},
                    {"I21", "UPDATE servidor2", "falta o IP"},
                    {"I22", "UPDATE servidor2 1.1.1.1 extra", "argumento a mais"},
            };
            for (String[] t : invalidos) verificar(t[0], t[1] + " (" + t[2] + ")", ERRO_ENTRADA, pedir(reg, t[1]));
            verificar("I23", "QUERY servidor2 após UPDATEs inválidos (inalterado)", "ANSWER servidor2 192.168.0.20", pedir(cli, "QUERY servidor2"));

            System.out.println("\n=== D) UPDATE SEM AUTORIZAÇÃO ===");
            verificar("I24", "UPDATE servidor3 6.6.6.6 (cliente comum)", "ERRO NAO_AUTORIZADO", pedir(intruso, "UPDATE servidor3 6.6.6.6"));
            try (ClienteUDPSeguro tokenErrado = sessao("REGISTRADOR:token-invalido")) {
                verificar("I25", "UPDATE servidor3 6.6.6.6 (token errado)", "ERRO NAO_AUTORIZADO", pedir(tokenErrado, "UPDATE servidor3 6.6.6.6"));
            }
            verificar("I26", "QUERY servidor3 (inalterado)", "ANSWER servidor3 192.168.0.30", pedir(cli, "QUERY servidor3"));

            System.out.println("\n=== E) UPDATE VÁLIDO como registrador autorizado ===");
            verificar("V11", "UPDATE servidor1 192.168.0.111", "OK UPDATED servidor1 192.168.0.111", pedir(reg, "UPDATE servidor1 192.168.0.111"));
            verificar("V12", "QUERY servidor1 (já atualizado)", "ANSWER servidor1 192.168.0.111", pedir(cli, "QUERY servidor1"));
            verificar("V13", "UPDATE servidor11 192.168.0.110 (nome novo)", "OK UPDATED servidor11 192.168.0.110", pedir(reg, "UPDATE servidor11 192.168.0.110"));
            verificar("V14", "QUERY servidor11", "ANSWER servidor11 192.168.0.110", pedir(cli, "QUERY servidor11"));
            verificar("V15", "UPDATE servidor7 0.0.0.0 (limite inferior)", "OK UPDATED servidor7 0.0.0.0", pedir(reg, "UPDATE servidor7 0.0.0.0"));
            verificar("V16", "UPDATE servidor8 255.255.255.255 (limite superior)", "OK UPDATED servidor8 255.255.255.255", pedir(reg, "UPDATE servidor8 255.255.255.255"));
            verificar("V17", "UPDATE db.local 10.0.0.15 (nome com ponto)", "OK UPDATED db.local 10.0.0.15", pedir(reg, "UPDATE db.local 10.0.0.15"));
            verificar("V18", "QUERY db.local", "ANSWER db.local 10.0.0.15", pedir(cli, "QUERY db.local"));
            verificar("V19", "UPDATE srv_1-a 172.16.0.1 (nome com _ e -)", "OK UPDATED srv_1-a 172.16.0.1", pedir(reg, "UPDATE srv_1-a 172.16.0.1"));

            System.out.println("\n=== F) NOTIFICAÇÃO a assinantes ===");
            try (ClienteUDPSeguro c1 = sessao(""); ClienteUDPSeguro c2 = sessao("")) {
                verificar("V20", "C1: SUBSCRIBE", "OK ASSINADO", pedir(c1, "SUBSCRIBE"));
                verificar("V21", "C2: SUBSCRIBE", "OK ASSINADO", pedir(c2, "SUBSCRIBE"));
                verificar("V22", "C1: SUBSCRIBE de novo (idempotente)", "OK ASSINADO", pedir(c1, "SUBSCRIBE"));
                verificar("V23", "registrador: UPDATE servidor5 192.168.0.55", "OK UPDATED servidor5 192.168.0.55", pedir(reg, "UPDATE servidor5 192.168.0.55"));
                verificar("V24", "C1 recebe NOTIFY", "NOTIFY servidor5 192.168.0.55", aguardar(c1, 2000));
                verificar("V25", "C2 recebe NOTIFY", "NOTIFY servidor5 192.168.0.55", aguardar(c2, 2000));
                verificar("V26", "C1 NÃO recebe NOTIFY duplicado", SEM_RESPOSTA, aguardar(c1, 1200));
                verificar("I27", "intruso (não assinou) não recebe NOTIFY", SEM_RESPOSTA, aguardar(intruso, 800));
            }

            System.out.println("\n=== G) FALHAS DE INTEGRIDADE / CIFRA / SESSÃO (o servidor deve DESCARTAR sem responder) ===");
            cli.enviarAdulterado("QUERY servidor1", Adulteracao.MAC_CORROMPIDO);
            verificar("S01", "pacote com HMAC corrompido", SEM_RESPOSTA, aguardar(cli, 1500));
            cli.enviarAdulterado("QUERY servidor1", Adulteracao.CIFRADO_ADULTERADO);
            verificar("S02", "pacote com texto cifrado adulterado", SEM_RESPOSTA, aguardar(cli, 1500));
            cli.enviarAdulterado("QUERY servidor1", Adulteracao.HASH_SEM_CHAVE);
            verificar("S03", "SHA-256 sem chave no lugar do HMAC", SEM_RESPOSTA, aguardar(cli, 1500));
            reg.enviarDuplicado("UPDATE servidor6 192.168.0.66");
            verificar("S04", "replay: 1º pacote é aceito", "OK UPDATED servidor6 192.168.0.66", aguardar(reg, 1500));
            verificar("S05", "replay: 2º pacote idêntico é descartado", SEM_RESPOSTA, aguardar(reg, 1500));
            verificar("S06", "sessão inexistente (sid falso)", SEM_RESPOSTA, bruto("0123456789abcdef.C.1.AAAA.BBBB.CCCC"));
            verificar("S07", "datagrama lixo", SEM_RESPOSTA, bruto("isto-nao-e-um-pacote"));
            verificar("S08", "KEYX malformado", SEM_RESPOSTA, bruto("KEYX:zzzz:AAAA"));
            String hs;
            try (ClienteUDPSeguro ruim = new ClienteUDPSeguro(DNS, 0, MUDO)) {
                ruim.handshake("", true);
                hs = "handshake concluído";
            } catch (SocketTimeoutException e) {
                hs = SEM_RESPOSTA;
            }
            verificar("S09", "handshake com cifra RSA corrompida", SEM_RESPOSTA, hs);
            verificar("S10", "servidor segue funcionando após os ataques", "ANSWER servidor1 192.168.0.111", pedir(cli, "QUERY servidor1"));
        }
        System.out.printf("%n=== RESULTADO: %d/%d verificações passaram ===%n", passou, total);
        System.exit(passou == total ? 0 : 1);
    }
}
