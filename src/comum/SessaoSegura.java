package comum;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estado de uma sessão segura entre dois processos, criado no handshake:
 *  - chaveAes: chave simétrica (AES-128) que cifra as mensagens;
 *  - chaveMac: chave do HMAC-SHA256 que garante integridade + autenticidade;
 *  - credencial: texto opcional enviado (cifrado com RSA) no handshake, usado para autorização
 *    (ex.: "REGISTRADOR:token", "ORIGEM:segredo").
 *
 * Os contadores de sequência impedem replay (reenvio de pacote válido capturado)
 * e, junto com a "direção" (C/S) coberta pelo HMAC, impedem reflexão de pacotes.
 */
public final class SessaoSegura {
    private final String sid;
    private final byte[] chaveAes;
    private final byte[] chaveMac;
    private final String credencial;
    private final boolean souCliente;
    private final AtomicLong seqEnvio = new AtomicLong();
    private final AtomicLong ultimoSeqRecebido = new AtomicLong();
    private volatile InetSocketAddress endereco; // usado só em servidores UDP

    SessaoSegura(String sid, byte[] chaveAes, byte[] chaveMac, String credencial, boolean souCliente) {
        this.sid = sid;
        this.chaveAes = chaveAes;
        this.chaveMac = chaveMac;
        this.credencial = credencial;
        this.souCliente = souCliente;
    }

    public String sid() { return sid; }
    public String credencial() { return credencial; }
    public boolean souCliente() { return souCliente; }
    public InetSocketAddress endereco() { return endereco; }
    public void setEndereco(InetSocketAddress endereco) { this.endereco = endereco; }

    byte[] chaveAes() { return chaveAes; }
    byte[] chaveMac() { return chaveMac; }
    long proximoSeq() { return seqEnvio.incrementAndGet(); }

    /** Aceita o número de sequência somente se for maior que o último aceito (anti-replay). */
    boolean registrarSeqRecebido(long seq) {
        while (true) {
            long atual = ultimoSeqRecebido.get();
            if (seq <= atual) return false;
            if (ultimoSeqRecebido.compareAndSet(atual, seq)) return true;
        }
    }
}
