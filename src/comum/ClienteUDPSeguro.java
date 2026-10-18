package comum;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Predicate;

/** Cliente UDP com a mesma criptografia híbrida (handshake RSA + AES/HMAC por datagrama). */
public final class ClienteUDPSeguro implements AutoCloseable {
    private final DatagramSocket socket;
    private final InetSocketAddress servidor;
    private final Log log;
    private SessaoSegura sessao;

    public ClienteUDPSeguro(InetSocketAddress servidor, int portaLocal, Log log) throws SocketException {
        this.socket = new DatagramSocket(portaLocal);
        this.servidor = servidor;
        this.log = log;
    }

    public void handshake(String credencial, boolean corromper) throws IOException, ErroIntegridadeException {
        String resp = trocar("HELLO", r -> r.startsWith("PUBKEY:"));
        var chavePublica = CanalSeguro.publicaDeBase64(resp.substring(7));
        SessaoSegura nova = CanalSeguro.novaSessaoCliente(credencial);
        String keyx = CanalSeguro.montarKEYX(nova, chavePublica, corromper);
        log.info("Handshake: chaves AES+HMAC sorteadas e enviadas cifradas com RSA-2048 para " + servidor
                + " (sid=" + nova.sid() + ")");
        String conf = trocar(keyx, r -> r.startsWith(nova.sid() + "."));
        String texto = CanalSeguro.abrir(nova, conf);
        if (!"HANDSHAKE_OK".equals(texto)) throw new ErroIntegridadeException("confirmação de handshake inesperada");
        this.sessao = nova;
        log.info("Handshake concluído: canal AES+HMAC estabelecido com " + servidor);
    }

    /** Envia texto puro e espera uma resposta aceita pelo filtro (até 3 tentativas — UDP não garante entrega). */
    private String trocar(String envio, Predicate<String> aceita) throws IOException {
        for (int tentativa = 1; tentativa <= 3; tentativa++) {
            enviarBruto(envio);
            try {
                String r = receberBruto(2000);
                if (aceita.test(r)) return r;
            } catch (SocketTimeoutException e) {
                log.erro("Sem resposta do servidor (tentativa " + tentativa + "/3)");
            }
        }
        throw new SocketTimeoutException("servidor sem resposta após 3 tentativas");
    }

    public synchronized void enviar(String texto) throws IOException {
        String pacote = CanalSeguro.proteger(sessao, texto);
        enviarBruto(pacote);
        log.info("ENVIADO  -> " + servidor + " [AES-CBC+HMAC] " + Log.resumo(texto, 90)
                + " | pacote=" + Log.resumo(pacote, 40));
    }

    public synchronized void enviarAdulterado(String texto, Adulteracao modo) throws IOException {
        enviarBruto(CanalSeguro.proteger(sessao, texto, modo));
        log.seguranca("ENVIADO  -> " + servidor + " pacote ADULTERADO (" + modo + ") para teste: "
                + Log.resumo(texto, 60));
    }

    /** Teste de replay: protege UMA vez e envia o MESMO pacote duas vezes (o servidor deve aceitar só a primeira). */
    public synchronized void enviarDuplicado(String texto) throws IOException {
        String pacote = CanalSeguro.proteger(sessao, texto);
        enviarBruto(pacote);
        enviarBruto(pacote);
        log.seguranca("ENVIADO  -> " + servidor + " o MESMO pacote 2x (teste de replay): " + Log.resumo(texto, 60));
    }

    /** Recebe, valida o HMAC e decifra. timeoutMs = 0 espera indefinidamente. */
    public String receber(int timeoutMs) throws IOException, ErroIntegridadeException {
        String pacote = receberBruto(timeoutMs);
        try {
            String texto = CanalSeguro.abrir(sessao, pacote);
            log.info("RECEBIDO <- " + servidor + " [HMAC OK] " + Log.resumo(texto, 90));
            return texto;
        } catch (ErroIntegridadeException e) {
            log.seguranca("RECEBIDO <- " + servidor + " [HMAC/INTEGRIDADE FALHOU] " + e.getMessage());
            throw e;
        }
    }

    private void enviarBruto(String texto) throws IOException {
        byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(dados, dados.length, servidor));
    }

    private String receberBruto(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        byte[] buf = new byte[8192];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8);
    }

    public SessaoSegura sessao() { return sessao; }

    @Override
    public void close() { socket.close(); }
}
