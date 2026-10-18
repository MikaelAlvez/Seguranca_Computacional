package comum;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

/**
 * Canal TCP seguro. Cada linha trafegada é um pacote protegido (AES-CBC + HMAC).
 *
 * Handshake (texto puro, só chaves PÚBLICAS circulam):
 *   cliente -> "HELLO"
 *   servidor -> "PUBKEY:<chave pública RSA em Base64>"
 *   cliente -> "KEYX:<sid>:<RSA(aes|hmac|credencial)>"
 *   servidor -> pacote protegido "HANDSHAKE_OK"  (prova que obteve as chaves)
 */
public final class CanalTCP implements AutoCloseable {
    private final Socket socket;
    private final BufferedReader entrada;
    private final PrintWriter saida;
    private final SessaoSegura sessao;
    private final Log log;
    private final String peer;
    private final Object travaEnvio = new Object();
    private volatile Adulteracao adulterarEnvio;   // teste: servidor "adulterado"

    private CanalTCP(Socket socket, BufferedReader entrada, PrintWriter saida,
                     SessaoSegura sessao, Log log) {
        this.socket = socket;
        this.entrada = entrada;
        this.saida = saida;
        this.sessao = sessao;
        this.log = log;
        this.peer = String.valueOf(socket.getRemoteSocketAddress());
    }

    // ---------------------------------------------------------------- lado CLIENTE

    public static CanalTCP conectar(String host, int porta, String credencial, boolean corromperHandshake,
                                    int timeoutMs, Log log) throws IOException, ErroIntegridadeException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, porta), 3000);
            s.setSoTimeout(timeoutMs);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);

            out.println("HELLO");
            String resp = in.readLine();
            if (resp == null || !resp.startsWith("PUBKEY:")) {
                throw new IOException("servidor encerrou ou não enviou a chave pública");
            }
            var chavePublica = CanalSeguro.publicaDeBase64(resp.substring(7));
            SessaoSegura sessao = CanalSeguro.novaSessaoCliente(credencial);
            out.println(CanalSeguro.montarKEYX(sessao, chavePublica, corromperHandshake));
            log.info("Handshake: chaves AES+HMAC sorteadas e enviadas cifradas com RSA-2048 para "
                    + host + ":" + porta + " (sid=" + sessao.sid() + ")");

            String conf = in.readLine();
            if (conf == null) {
                throw new IOException("servidor encerrou a conexão durante o handshake "
                        + "(provável erro de decifragem RSA)");
            }
            String texto = CanalSeguro.abrir(sessao, conf);   // pode lançar ErroIntegridadeException
            if (!"HANDSHAKE_OK".equals(texto)) throw new ErroIntegridadeException("confirmação de handshake inesperada");
            log.info("Handshake concluído: canal AES+HMAC estabelecido com " + host + ":" + porta);
            return new CanalTCP(s, in, out, sessao, log);
        } catch (IOException | ErroIntegridadeException | RuntimeException e) {
            try { s.close(); } catch (IOException ignorado) { /* já falhou */ }
            throw e;
        }
    }

    // ---------------------------------------------------------------- lado SERVIDOR

    public static CanalTCP aceitar(Socket s, KeyPair par, int timeoutMs, Log log,
                                   Adulteracao adulterarEnvio) throws IOException, ErroIntegridadeException {
        try {
            s.setSoTimeout(timeoutMs);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);

            String hello = in.readLine();
            if (!"HELLO".equals(hello)) throw new ErroIntegridadeException("handshake inválido (esperado HELLO)");
            out.println("PUBKEY:" + CanalSeguro.publicaParaBase64(par.getPublic()));

            String keyx = in.readLine();
            if (keyx == null) throw new IOException("cliente encerrou durante o handshake");
            SessaoSegura sessao = CanalSeguro.processarKEYX(keyx, par.getPrivate());

            CanalTCP canal = new CanalTCP(s, in, out, sessao, log);
            canal.adulterarEnvio = adulterarEnvio;
            log.info("Handshake: chaves da sessão " + sessao.sid() + " decifradas com a chave privada RSA (cliente "
                    + canal.peer + ")");
            canal.enviar("HANDSHAKE_OK");
            s.setSoTimeout(0);
            return canal;
        } catch (IOException | ErroIntegridadeException | RuntimeException e) {
            try { s.close(); } catch (IOException ignorado) { /* já falhou */ }
            throw e;
        }
    }

    // ---------------------------------------------------------------- uso

    public void enviar(String texto) throws IOException {
        synchronized (travaEnvio) {
            Adulteracao ad = adulterarEnvio;
            String pacote = (ad == null) ? CanalSeguro.proteger(sessao, texto)
                                         : CanalSeguro.proteger(sessao, texto, ad);
            escrever(pacote);
            log.info("ENVIADO  -> " + peer + " [AES-CBC+HMAC] " + Log.resumo(texto, 90)
                    + " | pacote=" + Log.resumo(pacote, 40));
        }
    }

    /** Envia um pacote DELIBERADAMENTE adulterado (simulação de ataque/erro de canal). */
    public void enviarAdulterado(String texto, Adulteracao modo) throws IOException {
        synchronized (travaEnvio) {
            escrever(CanalSeguro.proteger(sessao, texto, modo));
            log.seguranca("ENVIADO  -> " + peer + " pacote ADULTERADO (" + modo + ") para teste: "
                    + Log.resumo(texto, 60));
        }
    }

    /** Teste de replay: protege UMA vez e envia o MESMO pacote duas vezes (o servidor deve recusar o 2º). */
    public void enviarDuplicado(String texto) throws IOException {
        synchronized (travaEnvio) {
            String pacote = CanalSeguro.proteger(sessao, texto);
            escrever(pacote);
            escrever(pacote);
            log.seguranca("ENVIADO  -> " + peer + " o MESMO pacote 2x (teste de replay): " + Log.resumo(texto, 60));
        }
    }

    private void escrever(String pacote) throws IOException {
        saida.println(pacote);
        if (saida.checkError()) throw new IOException("falha ao escrever no socket (conexão encerrada?)");
    }

    /** Lê e valida a próxima mensagem; devolve null no fim da conexão. */
    public String receber() throws IOException, ErroIntegridadeException {
        String linha = entrada.readLine();
        if (linha == null) return null;
        try {
            String texto = CanalSeguro.abrir(sessao, linha);
            log.info("RECEBIDO <- " + peer + " [HMAC OK] " + Log.resumo(texto, 90));
            return texto;
        } catch (ErroIntegridadeException e) {
            log.seguranca("RECEBIDO <- " + peer + " [HMAC/INTEGRIDADE FALHOU] " + e.getMessage());
            throw e;
        }
    }

    public SessaoSegura sessao() { return sessao; }
    public String peer() { return peer; }
    public boolean fechado() { return socket.isClosed(); }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignorado) { /* nada a fazer */ }
    }
}
