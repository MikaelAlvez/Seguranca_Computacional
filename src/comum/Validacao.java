package comum;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validações de entrada centralizadas (nomes, IPs, arquivos, serviços). */
public final class Validacao {
    private Validacao() {}

    private static final Pattern NOME_DNS = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern ARQUIVO = Pattern.compile("^arquivo([1-9][0-9]{0,3})\\.ext$");

    public static boolean nomeDnsValido(String s) { return s != null && NOME_DNS.matcher(s).matches(); }

    public static boolean ipv4Valido(String s) { return s != null && IPV4.matcher(s).matches(); }

    /** "arquivoN.ext" com 1 &lt;= N &lt;= max → N; caso contrário -1. */
    public static int numeroArquivo(String nome, int max) {
        if (nome == null) return -1;
        var m = ARQUIVO.matcher(nome);
        if (!m.matches()) return -1;
        int n = Integer.parseInt(m.group(1));
        return (n >= 1 && n <= max) ? n : -1;
    }

    /** "/arquivoN.ext" → N (ou -1). */
    public static int numeroArquivoCaminho(String caminho, int max) {
        if (caminho == null || !caminho.startsWith("/")) return -1;
        return numeroArquivo(caminho.substring(1), max);
    }

    /** Minúsculas, sem acentos, espaços colapsados: "Calcular Subtração" → "calcular subtracao". */
    public static String normalizar(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
    }
}
