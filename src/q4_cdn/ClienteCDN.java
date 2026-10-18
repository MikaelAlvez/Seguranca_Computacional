package q4_cdn;

import comum.Adulteracao;
import comum.CanalTCP;
import comum.ClienteUDPSeguro;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * QUESTÃO 4 — Cliente (gerador de tráfego).
 *   1) pergunta ao Router (UDP cifrado) qual Edge usar;   2) abre TCP seguro com o Edge e faz GETs.
 *
 * Uso: java q4_cdn.ClienteCDN <id> [--local BR-NE] [--edge host:porta] [--handshake-invalido]
 *                             [--get arquivoN.ext | --get-sem-hmac arquivoN.ext | --dormir seg]... [--interativo]
 *   --edge            pula o Router e conecta direto no Edge (usado no caso 3);
 *   --get-sem-hmac    envia o GET com SHA-256 SEM chave no lugar do HMAC (atacante sem a chave);
 *   --handshake-invalido  corrompe o handshake RSA (erro de decifragem no Edge);
 *   --carga N MAX     gerador de tráfego: N requisições a arquivos sorteados entre 1 e MAX, com resumo HIT/MISS.
 */
public class ClienteCDN {
    private final Log log;
    private String localizacao = "BR-NE";
    private String edgeFixo;                       // "host:porta" (opcional)
    private boolean handshakeInvalido;
    private CanalTCP canal;
    private String edgeEmUso;
    private int hits, misses;                    // estatísticas do gerador de tráfego
    private double msHit, msMiss;

    public ClienteCDN(String id) { this.log = new Log("cdn-cliente-" + id); }

    /** Pergunta ao Router (UDP cifrado) qual Edge atende esta localização. Devolve "host:porta" ou null. */
    private String consultarRouter(String arquivo) {
        try (ClienteUDPSeguro udp = new ClienteUDPSeguro(new InetSocketAddress(Config.HOST, Config.ROUTER_PORTA), 0, log)) {
            udp.handshake("", false);
            udp.enviar("ONDE|" + arquivo + "|" + localizacao);
            String r = udp.receber(3000);
            if (r.startsWith("EDGE|")) {
                String[] p = r.split("\\|");
                log.info("ROUTER indicou o Edge " + p[3] + " (" + p[1] + ":" + p[2] + ") para a região " + localizacao);
                return p[1] + ":" + p[2];
            }
            log.erro("Router respondeu: " + r.replace("ERRO|", ""));
        } catch (SocketTimeoutException e) {
            log.erro("Router sem resposta: " + e.getMessage());
        } catch (IOException e) {
            log.erro("Falha ao falar com o Router: " + e.getMessage());
        } catch (ErroIntegridadeException e) {
            log.seguranca("Resposta do Router rejeitada: " + e.getMessage());
        }
        return null;
    }

    private boolean conectarEdge(String arquivo) {
        if (canal != null && !canal.fechado()) return true;          // já há canal seguro aberto com um Edge: reaproveita
        String alvo = edgeFixo != null ? edgeFixo : consultarRouter(arquivo);
        if (alvo == null) return false;
        fecharCanal();
        try {
            String[] hp = alvo.split(":");
            canal = CanalTCP.conectar(hp[0], Integer.parseInt(hp[1]), "", handshakeInvalido, 5000, log);
            edgeEmUso = alvo;
            return true;
        } catch (ErroIntegridadeException | IOException | NumberFormatException e) {
            log.erro("Handshake/conexão com o Edge " + alvo + " falhou: " + e.getMessage());
            return false;
        }
    }

    private void fecharCanal() { if (canal != null) { canal.close(); canal = null; } }

    void get(String arquivo, boolean semHmac) {
        if (!conectarEdge(arquivo)) return;
        try {
            long t0 = System.nanoTime();
            String comando = "GET /" + arquivo;
            if (semHmac) canal.enviarAdulterado(comando, Adulteracao.HASH_SEM_CHAVE);
            else canal.enviar(comando);
            String r = canal.receber();
            double ms = (System.nanoTime() - t0) / 1e6;
            if (r == null) {
                log.erro("Conexão ENCERRADA pelo Edge (HMAC ausente/inválido) — nenhuma resposta recebida.");
                fecharCanal();
                return;
            }
            String[] partes = r.split("\n\n", 2);
            if (partes[0].contains("Cache: HIT")) { hits++; msHit += ms; }
            else if (partes[0].contains("Cache: MISS")) { misses++; msMiss += ms; }
            String cabecalho = String.join(" | ", partes[0].split("\n"));
            String corpo = partes.length > 1 ? Log.resumo(partes[1], 50) : "";
            log.info(String.format("RESPOSTA para GET /%s em %.1f ms -> %s%s", arquivo, ms, cabecalho,
                    corpo.isEmpty() ? "" : " | corpo: " + corpo));
        } catch (ErroIntegridadeException e) {
            log.seguranca("Resposta do Edge rejeitada: " + e.getMessage());
        } catch (IOException e) {
            log.erro("Conexão com o Edge encerrada (" + e.getMessage() + ") — "
                    + (semHmac ? "esperado: o Edge fecha o socket quando o HMAC não confere." : "tente novamente."));
            fecharCanal();
        }
    }

    /** Gerador de tráfego: n requisições a arquivos sorteados entre 1 e max (semente fixa = reproduzível). */
    void carga(int n, int max) {
        java.util.Random sorteio = new java.util.Random(42);
        for (int i = 0; i < n; i++) get("arquivo" + (1 + sorteio.nextInt(max)) + ".ext", false);
        int total = hits + misses;
        log.info(String.format("RESUMO DA CARGA: %d requisições | HIT=%d (média %.1f ms) | MISS=%d (média %.1f ms) | taxa de acerto do cache = %.0f%%",
                total, hits, hits == 0 ? 0 : msHit / hits, misses, misses == 0 ? 0 : msMiss / misses,
                total == 0 ? 0 : 100.0 * hits / total));
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java q4_cdn.ClienteCDN <id> [--local BR-NE] [--edge host:porta] [--handshake-invalido] "
                    + "[--get arquivoN.ext | --get-sem-hmac arquivoN.ext | --dormir seg | --carga N MAX]... [--interativo]");
            return;
        }
        ClienteCDN c = new ClienteCDN(args[0]);
        List<String[]> acoes = new ArrayList<>();
        boolean interativo = false;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--local" -> c.localizacao = args[++i];
                    case "--edge" -> c.edgeFixo = args[++i];
                    case "--handshake-invalido" -> c.handshakeInvalido = true;
                    case "--get" -> acoes.add(new String[]{"get", args[++i]});
                    case "--get-sem-hmac" -> acoes.add(new String[]{"semhmac", args[++i]});
                    case "--dormir" -> acoes.add(new String[]{"dormir", args[++i]});
                    case "--carga" -> { acoes.add(new String[]{"carga", args[i + 1], args[i + 2]}); i += 2; }
                    case "--interativo" -> interativo = true;
                    default -> { System.out.println(Config.MSG_ERRO_ENTRADA + " (opção: " + args[i] + ")"); return; }
                }
            }
            for (String[] a : acoes) {
                switch (a[0]) {
                    case "get" -> c.get(a[1], false);
                    case "semhmac" -> c.get(a[1], true);
                    case "carga" -> c.carga(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                    case "dormir" -> {
                        c.log.info("Aguardando " + a[1] + " s...");
                        Thread.sleep(Long.parseLong(a[1]) * 1000);
                    }
                    default -> { }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (interativo) {
            Scanner teclado = new Scanner(System.in);
            System.out.println("Digite o nome do arquivo (ex.: arquivo20.ext) ou 'sair':");
            while (teclado.hasNextLine()) {
                String linha = teclado.nextLine().trim();
                if (linha.equalsIgnoreCase("sair")) break;
                c.get(linha, false);
            }
        }
        c.fecharCanal();
    }
}
