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
import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * QUESTÃO 4 — EDGE SERVER (nó de borda / cache).
 *
 * É servidor TCP (clientes e Origem conversam com ele) e cliente TCP (busca na Origem em caso de MISS).
 * Cache: ConcurrentHashMap<caminho, EntradaCache> com TTL (padrão 10 s) + invalidação ativa.
 *
 *   GET /arquivoN.ext   -> 200 OK | Cache: HIT/MISS | Length | corpo     (ou 400/502)
 *   INVALIDATE /arq...  -> 200 OK  (somente sessão com a credencial da Origem; senão 403)
 *
 * Segurança: handshake RSA -> AES+HMAC. Mensagem sem HMAC válido (ou "hash sem chave")
 * => o Edge ENCERRA a conexão e registra alerta, sem tocar na tabela de cache.
 *
 * Uso: java q4_cdn.ServidorEdge <porta>
 */
public class ServidorEdge {
    private record EntradaCache(String corpo, long expiraEmMs) {}

    private final int porta;
    private final String nome;
    private final Log log;
    private final KeyPair par = CanalSeguro.gerarParRSA();
    private final Map<String, EntradaCache> cache = new ConcurrentHashMap<>();
    private final long ttlMs = Config.CDN_TTL_SEGUNDOS * 1000L;

    public ServidorEdge(int porta) {
        this.porta = porta;
        this.nome = "edge-" + porta;
        this.log = new Log("cdn-" + nome);
    }

    public void executar() throws IOException {
        // faxineiro: remove entradas vencidas e registra a expiração por TTL
        ScheduledExecutorService faxina = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "faxina-ttl");
            t.setDaemon(true);
            return t;
        });
        faxina.scheduleAtFixedRate(() -> {
            long agora = System.currentTimeMillis();
            cache.entrySet().removeIf(e -> {
                boolean venceu = e.getValue().expiraEmMs() <= agora;
                if (venceu) log.info("TTL EXPIROU: " + e.getKey() + " removido do cache (TTL=" + Config.CDN_TTL_SEGUNDOS + " s)");
                return venceu;
            });
        }, 1, 1, TimeUnit.SECONDS);

        ServerSocket servidor = new ServerSocket(porta);
        log.info("Edge " + nome + " pronto na porta " + porta + " (cache vazio, TTL=" + Config.CDN_TTL_SEGUNDOS + " s)");
        while (true) {
            Socket s = servidor.accept();
            new Thread(() -> atender(s), "conexao-" + s.getPort()).start();
        }
    }

    private void atender(Socket s) {
        CanalTCP canal;
        try {
            canal = CanalTCP.aceitar(s, par, 5000, log, null);
        } catch (ErroIntegridadeException | IOException e) {
            log.seguranca("ALERTA: handshake inválido de " + s.getRemoteSocketAddress() + " (" + e.getMessage()
                    + ") -> conexão encerrada; cache intacto");
            return;
        }
        boolean daOrigem = ("ORIGEM:" + Config.SEGREDO_ORIGEM).equals(canal.sessao().credencial());
        try (canal) {
            String msg;
            while ((msg = canal.receber()) != null) {
                canal.enviar(tratar(msg, daOrigem));
            }
        } catch (ErroIntegridadeException e) {
            log.seguranca("ALERTA: mensagem sem HMAC válido (ou hash sem chave) de " + canal.peer() + " -> "
                    + "ENCERRANDO a conexão do socket; tabela de cache preservada (" + cache.size() + " entradas)");
        } catch (IOException e) {
            log.info("Conexão finalizada (" + e.getMessage() + ")");
        }
    }

    private String tratar(String msg, boolean daOrigem) {
        String[] p = msg.split(" ", 2);
        if (p.length != 2) return respostaErro("formato inválido");
        return switch (p[0]) {
            case "GET" -> tratarGet(p[1]);
            case "INVALIDATE" -> tratarInvalidate(p[1], daOrigem);
            default -> respostaErro("comando desconhecido");
        };
    }

    private String respostaErro(String motivo) {
        log.erro(Config.MSG_ERRO_ENTRADA + " (" + motivo + ")");
        return "400 Bad Request\nErro: " + Config.MSG_ERRO_ENTRADA;
    }

    private String tratarGet(String caminho) {
        if (Validacao.numeroArquivoCaminho(caminho, Config.CDN_TOTAL_ARQUIVOS) < 0) {
            // nome fora do padrão: NÃO consulta a Origem
            return respostaErro("GET inválido '" + Log.resumo(caminho, 30) + "'; a Origem não foi consultada");
        }
        long t0 = System.nanoTime();
        EntradaCache e = cache.get(caminho);
        if (e != null && e.expiraEmMs() > System.currentTimeMillis()) {
            log.info("CACHE HIT : " + caminho + " servido da memória em "
                    + String.format("%.2f", (System.nanoTime() - t0) / 1e6) + " ms");
            return resposta("HIT", e.corpo());
        }
        if (e != null) cache.remove(caminho);                    // venceu o TTL antes da faxina rodar
        log.info("CACHE MISS: " + caminho + " -> abrindo canal TCP seguro com a Origem (AES+HMAC)");
        try (CanalTCP origem = CanalTCP.conectar(Config.HOST, Config.ORIGEM_PORTA, "EDGE:" + nome, false, 5000, log)) {
            origem.enviar("GET " + caminho);
            String r = origem.receber();
            if (r == null) {
                return "502 Bad Gateway\nErro: a origem encerrou a conexão";
            }
            if (r.startsWith("404")) {                         // arquivo bem formado, mas inexistente na Origem
                log.erro("A Origem não possui " + caminho + " (404): repassado ao cliente; nada foi colocado em cache");
                return "404 Not Found\nErro: arquivo inexistente na origem";
            }
            if (!r.startsWith("200 OK")) {
                return "502 Bad Gateway\nErro: resposta inválida da origem";
            }
            String corpo = r.substring(r.indexOf("\n\n") + 2);
            cache.put(caminho, new EntradaCache(corpo, System.currentTimeMillis() + ttlMs));
            log.info("CACHE MISS resolvido: " + caminho + " baixado da Origem e salvo no cache em "
                    + String.format("%.2f", (System.nanoTime() - t0) / 1e6) + " ms (expira em " + Config.CDN_TTL_SEGUNDOS + " s)");
            return resposta("MISS", corpo);
        } catch (ErroIntegridadeException ex) {
            log.seguranca("Resposta da Origem rejeitada: " + ex.getMessage());
            return "502 Bad Gateway\nErro: integridade da origem";
        } catch (IOException ex) {
            log.erro("Origem indisponível: " + ex.getMessage());
            return "502 Bad Gateway\nErro: origem indisponível";
        }
    }

    private String tratarInvalidate(String caminho, boolean daOrigem) {
        if (!daOrigem) {
            log.seguranca("INVALIDATE NEGADO: sessão sem a credencial da Origem");
            return "403 Forbidden";
        }
        if (Validacao.numeroArquivoCaminho(caminho, Config.CDN_TOTAL_ARQUIVOS) < 0) return respostaErro("INVALIDATE inválido");
        boolean existia = cache.remove(caminho) != null;
        log.info("INVALIDATE da Origem (HMAC validado): " + caminho + (existia ? " removido do cache" : " (não estava em cache)"));
        return "200 OK\nInvalidated: " + caminho;
    }

    private static String resposta(String estado, String corpo) {
        return "200 OK\nCache: " + estado + "\nLength: " + corpo.getBytes(StandardCharsets.UTF_8).length + "\n\n" + corpo;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Uso: java q4_cdn.ServidorEdge <porta>   (ex.: 8001, 8002, 8003)");
            return;
        }
        try {
            new ServidorEdge(Integer.parseInt(args[0])).executar();
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (porta inválida)");
        } catch (IOException e) {
            System.out.println("Não foi possível iniciar o Edge: " + e.getMessage());
        }
    }
}
