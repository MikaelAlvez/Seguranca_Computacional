package q4_cdn;

import comum.CanalSeguro;
import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;
import comum.Validacao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * QUESTÃO 4 — ORIGIN SERVER (Servidor de Origem) da CDN.
 *
 * - TCP na porta fixa (8000); guarda 1000 arquivos mestre em disco (pasta dados_origem/).
 * - Atende "GET /arquivoN.ext" (somente dos Edges), sempre por canal seguro RSA -> AES+HMAC.
 * - Também é o ADMINISTRADOR da CDN: envia "INVALIDATE /arquivoN.ext" cifrado aos Edges,
 *   apresentando a credencial da Origem (entregue cifrada com RSA no handshake).
 *
 * Uso: java q4_cdn.ServidorOrigem [--resetar] [--edges 8001,8002,8003] [--cmd "invalidar arquivo30.ext"]... [--sair] [--admin]
 *      (--resetar: restaura os 1000 arquivos para a versão v1 antes de começar; use depois de rodar "atualizar")
 *      (--admin: não sobe o servidor; só envia os comandos de administração à CDN — útil em scripts)
 *      Console: "invalidar arquivoN.ext" | "atualizar arquivoN.ext" (reescreve o arquivo + invalida) | "sair"
 */
public class ServidorOrigem {
    private static final int TAMANHO_ARQUIVO = 1024;
    private final Path pasta = Path.of("dados_origem");
    private final Log log = new Log("cdn-origem");
    private final KeyPair par = CanalSeguro.gerarParRSA();
    private int[] portasEdges = Config.EDGE_PORTAS;
    private boolean resetar;                      // --resetar: restaura os 1000 arquivos para a versão v1 ao iniciar
    private static final java.util.regex.Pattern VERSAO = java.util.regex.Pattern.compile("^\\[v(\\d+)\\]");

    /** A versão atual vem do próprio arquivo ("[vN] ..."), então continua correta depois de reiniciar a Origem. */
    private int versaoAtual(Path arquivo) {
        try {
            String inicio = Files.readString(arquivo, StandardCharsets.UTF_8);
            var m = VERSAO.matcher(inicio.substring(0, Math.min(12, inicio.length())));
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (IOException ignorado) { /* arquivo ausente: trata como v1 */ }
        return 1;
    }

    private String conteudoPadrao(int n, int versao) {
        String base = "[v" + versao + "] Conteudo mestre do arquivo" + n + ".ext | ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < TAMANHO_ARQUIVO) sb.append(base);
        return sb.substring(0, TAMANHO_ARQUIVO);
    }

    private void prepararArquivos() throws IOException {
        Files.createDirectories(pasta);
        int gravados = 0;
        for (int n = 1; n <= Config.CDN_TOTAL_ARQUIVOS; n++) {
            Path f = pasta.resolve("arquivo" + n + ".ext");
            if (resetar || !Files.exists(f)) {
                Files.writeString(f, conteudoPadrao(n, 1), StandardCharsets.UTF_8);
                gravados++;
            }
        }
        log.info("Origem pronta: " + Config.CDN_TOTAL_ARQUIVOS + " arquivos em '" + pasta + "/' ("
                + gravados + (resetar ? " restaurados para a versão v1" : " criados agora") + ")");
    }

    public void executar() throws IOException {
        prepararArquivos();
        ServerSocket servidor = new ServerSocket(Config.ORIGEM_PORTA);
        log.info("Origin Server escutando TCP na porta " + Config.ORIGEM_PORTA + " (latência simulada: "
                + Config.ORIGEM_LATENCIA_MS + " ms)");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = servidor.accept();
                    new Thread(() -> atender(s), "edge-" + s.getPort()).start();
                } catch (IOException e) {
                    log.erro("Falha ao aceitar: " + e.getMessage());
                    return;
                }
            }
        }, "aceitador");
        t.start();
    }

    private void atender(Socket s) {
        try (CanalTCP canal = CanalTCP.aceitar(s, par, 5000, log, null)) {
            String msg;
            while ((msg = canal.receber()) != null) {
                canal.enviar(tratarGet(msg));
            }
        } catch (ErroIntegridadeException e) {
            log.seguranca("Conexão encerrada: " + e.getMessage());
        } catch (IOException e) {
            log.info("Conexão finalizada (" + e.getMessage() + ")");
        }
    }

    private String tratarGet(String msg) {
        String[] p = msg.split(" ", 2);
        int n = p.length == 2 && p[0].equals("GET") ? Validacao.numeroArquivoCaminho(p[1], Config.CDN_TOTAL_ARQUIVOS) : -1;
        if (n < 0) {
            log.erro(Config.MSG_ERRO_ENTRADA + " (requisição inválida: " + Log.resumo(msg, 40) + ")");
            return "400 Bad Request\nErro: " + Config.MSG_ERRO_ENTRADA;
        }
        try {
            Thread.sleep(Config.ORIGEM_LATENCIA_MS);                    // simula distância/latência até a origem
            String corpo = Files.readString(pasta.resolve("arquivo" + n + ".ext"), StandardCharsets.UTF_8);
            log.info("ORIGEM entregou " + p[1] + " lido do disco (" + corpo.length() + " bytes)");
            return "200 OK\nLength: " + corpo.getBytes(StandardCharsets.UTF_8).length + "\n\n" + corpo;
        } catch (IOException e) {
            log.erro("Arquivo não encontrado em disco: " + p[1]);
            return "404 Not Found";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "500 Internal Server Error";
        }
    }

    // ------------------------------------------------------------------ administração (INVALIDATE)

    /** Reescreve o arquivo (nova versão) e, em seguida, invalida o cache dos Edges. */
    void atualizar(String arquivo) {
        int n = Validacao.numeroArquivo(arquivo, Config.CDN_TOTAL_ARQUIVOS);
        if (n < 0) { log.erro(Config.MSG_ERRO_ENTRADA + " (arquivo inválido: " + arquivo + ")"); return; }
        int v = versaoAtual(pasta.resolve(arquivo)) + 1;
        try {
            Files.writeString(pasta.resolve(arquivo), conteudoPadrao(n, v), StandardCharsets.UTF_8);
            log.info("Arquivo " + arquivo + " atualizado na origem para a versão v" + v);
        } catch (IOException e) {
            log.erro("Falha ao atualizar " + arquivo + ": " + e.getMessage());
            return;
        }
        invalidar(arquivo);
    }

    void invalidar(String arquivo) {
        if (Validacao.numeroArquivo(arquivo, Config.CDN_TOTAL_ARQUIVOS) < 0) {
            log.erro(Config.MSG_ERRO_ENTRADA + " (arquivo inválido: " + arquivo + ")");
            return;
        }
        for (int porta : portasEdges) {
            try (CanalTCP canal = CanalTCP.conectar(Config.HOST, porta, "ORIGEM:" + Config.SEGREDO_ORIGEM, false, 3000, log)) {
                canal.enviar("INVALIDATE /" + arquivo);
                log.info("Edge:" + porta + " respondeu: " + Log.resumo(String.valueOf(canal.receber()), 60));
            } catch (IOException | ErroIntegridadeException e) {
                log.erro("Não foi possível invalidar no Edge " + porta + ": " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        ServidorOrigem origem = new ServidorOrigem();
        List<String[]> comandos = new ArrayList<>();
        boolean sair = false;
        boolean soAdmin = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--edges" -> {
                        String[] partes = args[++i].split(",");
                        origem.portasEdges = new int[partes.length];
                        for (int k = 0; k < partes.length; k++) origem.portasEdges[k] = Integer.parseInt(partes[k].trim());
                    }
                    case "--cmd" -> comandos.add(args[++i].trim().split("\\s+"));
                    case "--sair" -> sair = true;
                    case "--admin" -> soAdmin = true;      // só executa os comandos de administração (não sobe o servidor)
                    case "--resetar" -> origem.resetar = true;
                    default -> { System.out.println(Config.MSG_ERRO_ENTRADA + " (opção: " + args[i] + ")"); return; }
                }
            }
        } catch (RuntimeException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA);
            return;
        }
        if (soAdmin) {                                   // modo usado pelos scripts: a Origem já está rodando em outro processo
            for (String[] c : comandos) origem.executarComando(c);
            return;
        }
        try {
            origem.executar();
        } catch (IOException e) {
            System.out.println("Não foi possível iniciar a Origem: " + e.getMessage());
            return;
        }
        if (!comandos.isEmpty()) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            for (String[] c : comandos) origem.executarComando(c);
            if (sair) System.exit(0);
        } else {
            Scanner teclado = new Scanner(System.in);
            System.out.println("Comandos: 'invalidar arquivoN.ext' | 'atualizar arquivoN.ext' | 'sair'");
            while (teclado.hasNextLine()) {
                String[] c = teclado.nextLine().trim().split("\\s+");
                if (c[0].equalsIgnoreCase("sair")) System.exit(0);
                origem.executarComando(c);
            }
        }
    }

    private void executarComando(String[] c) {
        if (c.length == 2 && c[0].equalsIgnoreCase("invalidar")) invalidar(c[1]);
        else if (c.length == 2 && c[0].equalsIgnoreCase("atualizar")) atualizar(c[1]);
        else log.erro(Config.MSG_ERRO_ENTRADA + " (comando desconhecido)");
    }
}
