package comum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Log simples: escreve no console e em logs/&lt;componente&gt;.log (acrescenta; cada execução começa com um marcador).
 * Os logs de cada lado (envio, recebimento, validação de HMAC) servem de evidência dos testes.
 */
public final class Log {
    private static final Object TRAVA = new Object();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String componente;
    private final Path arquivo;
    private final boolean mudo;

    /** Log que descarta tudo (usado em tarefas repetitivas, como heartbeats). */
    public static Log mudo() { return new Log(); }

    private Log() { this.componente = "mudo"; this.arquivo = null; this.mudo = true; }

    public Log(String componente) {
        this.mudo = false;
        this.componente = componente;
        this.arquivo = Path.of(System.getProperty("logs.dir", "logs"), componente + ".log");
        try {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, "===== nova execução: " + LocalDateTime.now() + " =====" + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Não foi possível criar o arquivo de log: " + e.getMessage());
        }
    }

    public void info(String msg)      { escrever("INFO  ", msg); }
    public void seguranca(String msg) { escrever("SEGUR ", msg); }
    public void erro(String msg)      { escrever("ERRO  ", msg); }

    private void escrever(String nivel, String msg) {
        if (mudo) return;
        String linha = "[" + LocalTime.now().format(FMT) + "] [" + componente + "] " + nivel + msg;
        synchronized (TRAVA) {
            System.out.println(linha);
            try {
                Files.writeString(arquivo, linha + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                // log em arquivo é "melhor esforço"; o console já recebeu a linha
            }
        }
    }

    /** Encurta textos longos (e troca quebras de linha) para caber em uma linha de log. */
    public static String resumo(String texto, int max) {
        String t = texto.replace("\r", "").replace("\n", "\\n");
        return t.length() <= max ? t : t.substring(0, max) + "...(" + texto.length() + " chars)";
    }
}
