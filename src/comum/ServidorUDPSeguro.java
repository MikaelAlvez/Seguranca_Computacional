package comum;

import java.io.IOException;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base para servidores UDP seguros (Mini-DNS e Router GeoDNS).
 *
 * Cada datagrama é tratado por uma thread de um pool. As sessões ficam em um ConcurrentHashMap
 * (coleção thread-safe, como vimos nas práticas 2.5–2.8), indexadas pelo identificador de sessão.
 *
 * Regras de segurança:
 *  - pacote com sessão desconhecida ou HMAC inválido é DESCARTADO sem resposta e sem processamento;
 *  - o motivo é registrado no log.
 */
public abstract class ServidorUDPSeguro {
    protected final Log log;
    protected final DatagramSocket socket;
    protected final KeyPair par = CanalSeguro.gerarParRSA();
    protected final Map<String, SessaoSegura> sessoes = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    protected ServidorUDPSeguro(String nome, int porta) throws SocketException {
        this.log = new Log(nome);
        try {
            this.socket = new DatagramSocket(porta);
        } catch (BindException e) {
            throw new BindException("porta UDP " + porta + " já está em uso (" + e.getMessage() + ")");
        }
    }

    /** Loop principal: recebe datagramas e delega a threads do pool. */
    public final void executar() {
        log.info("Servidor UDP pronto na porta " + socket.getLocalPort()
                + " (RSA-2048 gerado; aguardando handshakes)");
        byte[] buf = new byte[8192];
        while (!socket.isClosed()) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dp);
            } catch (IOException e) {
                if (socket.isClosed()) break;
                log.erro("Falha ao receber datagrama: " + e.getMessage());
                continue;
            }
            // copia, pois o buffer é reutilizado no próximo receive
            String texto = new String(dp.getData(), 0, dp.getLength(), StandardCharsets.UTF_8);
            InetSocketAddress origem = (InetSocketAddress) dp.getSocketAddress();
            pool.submit(() -> {
                try {
                    tratarDatagrama(texto, origem);
                } catch (RuntimeException e) {
                    log.erro("Erro inesperado ao tratar datagrama de " + origem + ": " + e);
                }
            });
        }
    }

    private void tratarDatagrama(String texto, InetSocketAddress origem) {
        if ("HELLO".equals(texto)) {
            enviarBruto(origem, "PUBKEY:" + CanalSeguro.publicaParaBase64(par.getPublic()));
            log.info("HELLO de " + origem + " -> chave pública RSA enviada");
            return;
        }
        if (texto.startsWith("KEYX:")) {
            try {
                SessaoSegura s = CanalSeguro.processarKEYX(texto, par.getPrivate());
                s.setEndereco(origem);
                SessaoSegura existente = sessoes.putIfAbsent(s.sid(), s);
                if (existente != null) s = existente;     // retransmissão do KEYX (UDP): reaproveita a sessão
                log.info("Handshake: sessão " + s.sid() + " criada para " + origem
                        + " (chaves AES+HMAC decifradas com a chave privada RSA)");
                responder(s, "HANDSHAKE_OK");
            } catch (ErroIntegridadeException e) {
                log.seguranca("FALHA NO HANDSHAKE de " + origem + ": " + e.getMessage() + " -> descartado");
            }
            return;
        }
        int ponto = texto.indexOf('.');
        SessaoSegura s = ponto > 0 ? sessoes.get(texto.substring(0, ponto)) : null;
        if (s == null) {
            log.seguranca("Pacote de " + origem + " com sessão desconhecida/malformado -> descartado");
            return;
        }
        try {
            String msg = CanalSeguro.abrir(s, texto);
            s.setEndereco(origem);
            log.info("RECEBIDO <- " + origem + " [HMAC OK, sessão " + s.sid() + "] " + Log.resumo(msg, 90));
            tratarMensagem(s, msg);
        } catch (ErroIntegridadeException e) {
            log.seguranca("FALHA DE INTEGRIDADE em pacote de " + origem + " (" + e.getMessage()
                    + ") -> pacote UDP DESCARTADO sem processar");
        }
    }

    /** Implementada por cada servidor: trata uma mensagem JÁ autenticada e decifrada. */
    protected abstract void tratarMensagem(SessaoSegura sessao, String mensagem);

    /** Envia uma resposta protegida para o endereço atual da sessão. */
    protected final void responder(SessaoSegura s, String texto) {
        String pacote;
        synchronized (s) {   // numeração de sequência e envio na mesma ordem (o anti-replay exige ordem crescente)
            pacote = CanalSeguro.proteger(s, texto);
            enviarBruto(s.endereco(), pacote);
        }
        log.info("ENVIADO  -> " + s.endereco() + " [AES-CBC+HMAC] " + Log.resumo(texto, 90)
                + " | pacote=" + Log.resumo(pacote, 40));
    }

    private void enviarBruto(InetSocketAddress destino, String texto) {
        byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(dados, dados.length, destino));
        } catch (IOException e) {
            log.erro("Falha ao enviar para " + destino + ": " + e.getMessage());
        }
    }
}
