package comum;

/** Lançada quando um pacote falha na verificação (HMAC, formato, sessão, replay, RSA). */
public class ErroIntegridadeException extends Exception {
    private static final long serialVersionUID = 1L;

    public ErroIntegridadeException(String mensagem) { super(mensagem); }
    public ErroIntegridadeException(String mensagem, Throwable causa) { super(mensagem, causa); }
}
