package q4_cdn;

import comum.Adulteracao;
import comum.CanalTCP;
import comum.ClienteUDPSeguro;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * QUESTÃO 4 — Teste automatizado da CDN com informações VÁLIDAS e INVÁLIDAS.
 *
 * Sobe como PROCESSOS independentes: Origem (8000), 3 Edges (8001-8003) e Router (UDP 5354), com TTL curto (6 s)
 * e latência simulada DESLIGADA (para provar que HIT &lt; MISS pelo custo real do handshake/rede, não por atraso fictício).
 * Digita os comandos de administração no console da Origem e confere o que cada processo registrou.
 *
 * Uso: java q4_cdn.TesteQ4      (código de saída 0 somente se todas as verificações passarem)
 */
public class TesteQ4 {
    private static final int TTL = 6;
    private static final String SEM_RESPOSTA = "<sem resposta>";
    private static final String ENCERRADA = "<conexão encerrada>";
    private static final String ERRO_ENTRADA = "ERRO|" + Config.MSG_ERRO_ENTRADA;
    private static final Log MUDO = Log.mudo();
    private static final int[] EDGES = Config.EDGE_PORTAS;

    private static int total, passou;
    private static Proc origem, router;
    private static final Proc[] edge = new Proc[EDGES.length];

    // ------------------------------------------------------------------ processos

    private static final class Proc {
        final String nome;
        final Process proc;
        final BufferedWriter entrada;
        final List<String> saida = new ArrayList<>();

        Proc(String nome, String classe, String... args) throws IOException {
            this.nome = nome;
            List<String> cmd = new ArrayList<>(List.of(
                    System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dcdn.ttl=" + TTL, "-Dcdn.latencia=0",
                    "-cp", caminhoDasClasses(), classe));
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            entrada = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            Thread leitor = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) synchronized (saida) { saida.add(l); }
                } catch (IOException ignorado) { /* processo encerrado */ }
            }, "leitor-" + nome);
            leitor.setDaemon(true);
            leitor.start();
        }

        void comando(String linha) throws IOException { entrada.write(linha + "\n"); entrada.flush(); }

        int marca() { synchronized (saida) { return saida.size(); } }

        List<String> desde(int marca) {
            synchronized (saida) { return new ArrayList<>(saida.subList(Math.min(marca, saida.size()), saida.size())); }
        }

        int contar(int marca, String trecho) {
            int n = 0;
            for (String l : desde(marca)) if (l.contains(trecho)) n++;
            return n;
        }

        boolean contem(int marca, String trecho) { return contar(marca, trecho) > 0; }

        void parar() {
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /** Onde estão as classes compiladas (funciona em classpath, projeto modular e com pacote renomeado). */
    private static String caminhoDasClasses() {
        List<String> partes = new ArrayList<>();
        try {
            var cs = TesteQ4.class.getProtectionDomain().getCodeSource();
            if (cs != null) partes.add(new File(cs.getLocation().toURI()).getPath());
        } catch (Exception ignorado) { /* cai nas propriedades */ }
        for (String chave : new String[]{"java.class.path", "jdk.module.path"}) {
            String v = System.getProperty(chave);
            if (v == null || v.isBlank()) continue;
            for (String e : v.split(File.pathSeparator)) if (!e.isBlank() && !partes.contains(e)) partes.add(e);
        }
        return String.join(File.pathSeparator, partes);
    }

    private static void aguardarPronto(Proc p, String trecho) throws Exception {
        long fim = System.currentTimeMillis() + 25_000;
        while (!p.contem(0, trecho)) {
            if (!p.proc.isAlive() || System.currentTimeMillis() > fim) {
                Thread.sleep(200);
                throw new IllegalStateException("processo '" + p.nome + "' não subiu. Saída:\n" + String.join("\n", p.desde(0))
                        + "\n(Dica: porta em uso? Encerre Origem/Edges/Router que estiverem rodando e tente de novo.)");
            }
            Thread.sleep(50);
        }
    }

    private static void derrubar() {
        for (int i = 0; i < edge.length; i++) if (edge[i] != null) { edge[i].parar(); edge[i] = null; }
        if (router != null) { router.parar(); router = null; }
        if (origem != null) { origem.parar(); origem = null; }
    }

    /** Antes de subir qualquer processo, confere se as portas estão livres (evita abortar no meio com uma mensagem confusa). */
    private static void verificarPortasLivres() {
        List<String> ocupadas = new ArrayList<>();
        List<Integer> tcp = new ArrayList<>(List.of(Config.ORIGEM_PORTA));
        for (int p : EDGES) tcp.add(p);
        for (int p : tcp) {
            try {
                new ServerSocket(p).close();            // consegue abrir e fechar: a porta está livre
            } catch (IOException e) {
                ocupadas.add("TCP " + p);
            }
        }
        try {
            new DatagramSocket(Config.ROUTER_PORTA).close();
        } catch (IOException e) {
            ocupadas.add("UDP " + Config.ROUTER_PORTA);
        }
        if (ocupadas.isEmpty()) return;
        System.err.println("ERRO: portas já em uso: " + String.join(", ", ocupadas) + "  (o teste precisa de TCP 8000-8003 e UDP 5354 livres)");
        System.err.println("\nHá processos antigos ainda rodando e usando essas portas. Encerre-os e rode o teste de novo:");
        System.err.println("  - Eclipse: na view Debug/Console, botão vermelho (Terminate) em cada execução antiga");
        System.err.println("             (ou 'Terminate/Disconnect All'). Execuções interrompidas podem deixar processos-filho órfãos.");
        System.err.println("  - Linux/macOS: 'jps -l' lista os processos Java; depois 'kill <PID>'");
        System.err.println("                 ou, para uma porta: 'lsof -ti :PORTA | xargs kill'");
        System.err.println("  - Windows:     'netstat -ano | findstr :PORTA' e depois 'taskkill /PID <PID> /F'");
        System.exit(2);
    }

    private static void subirTudo() throws Exception {
        derrubar();
        origem = new Proc("origem", ServidorOrigem.class.getName(), "--resetar");   // sempre começa com todos os arquivos em v1
        aguardarPronto(origem, "escutando TCP");
        for (int i = 0; i < EDGES.length; i++) {
            edge[i] = new Proc("edge-" + EDGES[i], ServidorEdge.class.getName(), String.valueOf(EDGES[i]));
            aguardarPronto(edge[i], "pronto na porta");
        }
        router = new Proc("router", RouterGeoDNS.class.getName());
        aguardarPronto(router, "pronto na porta");
        Thread.sleep(300);
    }

    // ------------------------------------------------------------------ verificação

    private static void verificar(String id, String desc, String esperado, String obtido, boolean imprimir) {
        total++;
        boolean ok = esperado.equals(obtido);
        if (ok) passou++;
        if (imprimir || !ok) {
            System.out.printf("%-6s %-78s esperado=%-20s obtido=%-20s %s%n", id, cortar(desc, 78),
                    cortar(esperado, 20), cortar(obtido, 20), ok ? "OK" : "<<< FALHOU");
        }
    }

    private static void verificar(String id, String desc, boolean condicao) {
        verificar(id, desc, "true", String.valueOf(condicao), true);
    }

    private static String cortar(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    private static void titulo(String t) { System.out.println("\n################ " + t + " ################"); }

    // ------------------------------------------------------------------ cliente TCP (protocolo do Edge)

    private record Resp(String status, Map<String, String> cab, String corpo, double ms) {
        boolean ok200(String cache, int arq, String versao) {
            return status.equals("200 OK") && cache.equals(cab.get("Cache")) && "1024".equals(cab.get("Length"))
                    && corpo.length() == 1024 && corpo.startsWith("[" + versao + "] Conteudo mestre do arquivo" + arq + ".ext");
        }
    }

    private static CanalTCP abrir(int porta, String credencial) throws Exception {
        return CanalTCP.conectar(Config.HOST, porta, credencial, false, 5000, MUDO);
    }

    private static Resp interpretar(String bruto, double ms) {
        String[] partes = bruto.split("\n\n", 2);
        String[] linhas = partes[0].split("\n");
        Map<String, String> cab = new HashMap<>();
        for (int i = 1; i < linhas.length; i++) {
            int k = linhas[i].indexOf(": ");
            if (k > 0) cab.put(linhas[i].substring(0, k), linhas[i].substring(k + 2));
        }
        return new Resp(linhas[0], cab, partes.length > 1 ? partes[1] : "", ms);
    }

    private static String lerOuNull(CanalTCP c) {
        try { return c.receber(); } catch (Exception e) { return null; }
    }

    /** Envia o texto e devolve a resposta interpretada (null se a conexão foi encerrada). */
    private static Resp pedir(CanalTCP c, String msg) throws Exception {
        long t0 = System.nanoTime();
        c.enviar(msg);
        String r = lerOuNull(c);
        return r == null ? null : interpretar(r, (System.nanoTime() - t0) / 1e6);
    }

    private static Resp get(CanalTCP c, String caminho) throws Exception { return pedir(c, "GET " + caminho); }

    private static boolean ok200(Resp r, String cache, int arq, String versao) { return r != null && r.ok200(cache, arq, versao); }

    private static String status(Resp r) { return r == null ? ENCERRADA : r.status(); }

    // ------------------------------------------------------------------ cliente UDP (Router)

    private static ClienteUDPSeguro sessaoRouter() throws Exception {
        ClienteUDPSeguro c = new ClienteUDPSeguro(new InetSocketAddress(Config.HOST, Config.ROUTER_PORTA), 0, MUDO);
        c.handshake("", false);
        return c;
    }

    private static String aguardarUDP(ClienteUDPSeguro c, int ms) throws Exception {
        try { return c.receber(ms); }
        catch (SocketTimeoutException e) { return SEM_RESPOSTA; }
        catch (ErroIntegridadeException e) { return "<resposta com integridade inválida>"; }
    }

    private static String perguntar(ClienteUDPSeguro c, String msg) throws Exception {
        c.enviar(msg);
        return aguardarUDP(c, 1500);
    }

    private static String brutoRouter(String texto) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] d = texto.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(d, d.length, InetAddress.getByName(Config.HOST), Config.ROUTER_PORTA));
            s.setSoTimeout(1500);
            DatagramPacket r = new DatagramPacket(new byte[8192], 8192);
            try {
                s.receive(r);
                return new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                return SEM_RESPOSTA;
            }
        }
    }

    // ================================================================== GRUPOS DE TESTE

    private static void grupoOrigem() {
        titulo("A) ORIGEM: 1000 arquivos mestre em disco");
        Path pasta = Path.of("dados_origem");
        int ok = 0;
        try {
            for (int n = 1; n <= Config.CDN_TOTAL_ARQUIVOS; n++) {
                Path f = pasta.resolve("arquivo" + n + ".ext");
                if (Files.exists(f) && Files.size(f) == 1024) ok++;
            }
        } catch (IOException e) { /* conta como falha */ }
        verificar("A01", "dados_origem/ tem arquivo1.ext ... arquivo1000.ext, todos com 1024 bytes", "1000", String.valueOf(ok), true);
        verificar("A02", "não existe arquivo1001.ext", "false", String.valueOf(Files.exists(pasta.resolve("arquivo1001.ext"))), true);
    }

    private static void grupoRouter() throws Exception {
        titulo("B) ROUTER/GeoDNS (UDP cifrado): localização -> Edge");
        try (ClienteUDPSeguro u = sessaoRouter()) {
            String[][] validos = {{"BR-NE", "8001"}, {"BR-SE", "8002"}, {"US", "8003"}, {"br-se", "8002"}, {"us", "8003"},
                    {"JP", "8001"}, {"xx-yy", "8001"}};
            for (int i = 0; i < validos.length; i++) {
                String p = validos[i][1];
                verificar("B0" + (i + 1), "ONDE|arquivo20.ext|" + validos[i][0] + (i >= 5 ? " (desconhecida: Edge padrão)" : ""),
                        "EDGE|127.0.0.1|" + p + "|edge-" + p, perguntar(u, "ONDE|arquivo20.ext|" + validos[i][0]), true);
            }
            String[][] invalidos = {
                    {"B10", "ONDE|arquivoXYZ.ext|BR-NE"}, {"B11", "ONDE|arquivo0.ext|BR-NE"}, {"B12", "ONDE|arquivo1001.ext|BR-NE"},
                    {"B13", "ONDE|arquivo20.ext|"}, {"B14", "ONDE|arquivo20.ext|BR-NE; DROP"}, {"B15", "ONDE|arquivo20.ext|%%%"},
                    {"B16", "ONDE|arquivo20.ext"}, {"B17", "ONDE|arquivo20.ext|BR-NE|extra"}, {"B18", "PING"}, {"B19", ""},
            };
            for (String[] t : invalidos) verificar(t[0], "'" + t[1] + "'", ERRO_ENTRADA, perguntar(u, t[1]), true);

            u.enviarAdulterado("ONDE|arquivo20.ext|BR-NE", Adulteracao.MAC_CORROMPIDO);
            verificar("B20", "pacote com HMAC corrompido", SEM_RESPOSTA, aguardarUDP(u, 1500), true);
            u.enviarAdulterado("ONDE|arquivo20.ext|BR-NE", Adulteracao.CIFRADO_ADULTERADO);
            verificar("B21", "pacote com texto cifrado adulterado", SEM_RESPOSTA, aguardarUDP(u, 1500), true);
            u.enviarAdulterado("ONDE|arquivo20.ext|BR-NE", Adulteracao.HASH_SEM_CHAVE);
            verificar("B22", "SHA-256 sem chave no lugar do HMAC", SEM_RESPOSTA, aguardarUDP(u, 1500), true);
            u.enviarDuplicado("ONDE|arquivo20.ext|BR-SE");
            verificar("B23", "replay: 1º pacote é respondido", "EDGE|127.0.0.1|8002|edge-8002", aguardarUDP(u, 1500), true);
            verificar("B24", "replay: 2º pacote idêntico é descartado", SEM_RESPOSTA, aguardarUDP(u, 1500), true);
            verificar("B25", "sessão inexistente (sid falso)", SEM_RESPOSTA, brutoRouter("0123456789abcdef.C.1.AAAA.BBBB.CCCC"), true);
            verificar("B26", "datagrama lixo", SEM_RESPOSTA, brutoRouter("isto-nao-e-um-pacote"), true);
            String hs;
            try (ClienteUDPSeguro ruim = new ClienteUDPSeguro(new InetSocketAddress(Config.HOST, Config.ROUTER_PORTA), 0, MUDO)) {
                ruim.handshake("", true);
                hs = "handshake concluído";
            } catch (SocketTimeoutException e) {
                hs = SEM_RESPOSTA;
            }
            verificar("B27", "handshake com cifra RSA corrompida", SEM_RESPOSTA, hs, true);
            verificar("B28", "Router segue funcionando após os ataques", "EDGE|127.0.0.1|8003|edge-8003", perguntar(u, "ONDE|arquivo20.ext|US"), true);
        }
    }

    private static void grupoHitMiss() throws Exception {
        titulo("C) CASO 1 - HIT x MISS nos 3 Edges: formato do protocolo, conteúdo e HMAC validado nas duas pontas");
        for (int i = 0; i < EDGES.length; i++) {
            int mO = origem.marca(), mE = edge[i].marca();
            try (CanalTCP c = abrir(EDGES[i], "")) {
                Resp miss = get(c, "/arquivo20.ext"), hit = get(c, "/arquivo20.ext");
                String p = "C0" + (i + 1);
                verificar(p + "a", "Edge " + EDGES[i] + ": 1ª GET /arquivo20.ext -> 200 OK, Cache: MISS, Length: 1024",
                        "true", String.valueOf(ok200(miss, "MISS", 20, "v1")), true);
                verificar(p + "b", "Edge " + EDGES[i] + ": 2ª GET /arquivo20.ext -> 200 OK, Cache: HIT, Length: 1024",
                        "true", String.valueOf(ok200(hit, "HIT", 20, "v1")), true);
                verificar(p + "c", "Edge " + EDGES[i] + ": log registra MISS e HIT, com HMAC validado nas duas pontas", "true",
                        String.valueOf(edge[i].contem(mE, "CACHE MISS") && edge[i].contem(mE, "CACHE HIT")
                                && edge[i].contem(mE, "[HMAC OK] GET /arquivo20.ext") && edge[i].contem(mE, "[HMAC OK] 200 OK")
                                && origem.contem(mO, "[HMAC OK] GET /arquivo20.ext")), true);
            }
        }
        try (CanalTCP c = abrir(EDGES[0], "")) {
            List<Integer> amostra = new ArrayList<>(List.of(1, 1000, 2, 999, 500));
            Random r = new Random(7);
            for (int k = 0; k < 25; k++) amostra.add(1 + r.nextInt(1000));
            int certos = 0;
            for (int n : amostra) {
                Resp a = get(c, "/arquivo" + n + ".ext");
                if (ok200(a, "MISS", n, "v1") || ok200(a, "HIT", n, "v1")) certos++;
            }
            verificar("C04", "amostra de " + amostra.size() + " arquivos (1, 2, 500, 999, 1000 e sorteados): conteúdo certo para cada nome",
                    String.valueOf(amostra.size()), String.valueOf(certos), true);
        }
        List<Double> tMiss = new ArrayList<>(), tHit = new ArrayList<>();
        try (CanalTCP c = abrir(EDGES[1], "")) {
            for (int n = 101; n <= 107; n++) {
                Resp m = get(c, "/arquivo" + n + ".ext"), h = get(c, "/arquivo" + n + ".ext");
                if (m != null && "MISS".equals(m.cab().get("Cache"))) tMiss.add(m.ms());
                if (h != null && "HIT".equals(h.cab().get("Cache"))) tHit.add(h.ms());
            }
        }
        Collections.sort(tMiss);
        Collections.sort(tHit);
        double medMiss = tMiss.isEmpty() ? 0 : tMiss.get(tMiss.size() / 2);
        double medHit = tHit.isEmpty() ? 1e9 : tHit.get(tHit.size() / 2);
        System.out.printf("       mediana MISS = %.1f ms | mediana HIT = %.1f ms (latência simulada da Origem desligada)%n", medMiss, medHit);
        verificar("C05", "a resposta HIT é mais rápida que a MISS na prática (mediana de 7 arquivos)", medHit < medMiss);
    }

    private static void grupoTtl() throws Exception {
        titulo("D) CASO 2 - expiração por TTL (" + TTL + " s)");
        int mE = edge[0].marca();
        try (CanalTCP c = abrir(EDGES[0], "")) {
            verificar("D01", "GET /arquivo30.ext: MISS", "true", String.valueOf(ok200(get(c, "/arquivo30.ext"), "MISS", 30, "v1")), true);
            verificar("D02", "GET /arquivo30.ext: HIT (dentro do TTL)", "true", String.valueOf(ok200(get(c, "/arquivo30.ext"), "HIT", 30, "v1")), true);
            System.out.println("       aguardando " + (TTL + 1) + " s para o TTL expirar...");
            Thread.sleep((TTL + 1) * 1000L);
            verificar("D03", "log do Edge registra 'TTL EXPIROU' para /arquivo30.ext", edge[0].contem(mE, "TTL EXPIROU: /arquivo30.ext"));
            verificar("D04", "GET /arquivo30.ext após o TTL: MISS (nova busca segura na Origem)", "true",
                    String.valueOf(ok200(get(c, "/arquivo30.ext"), "MISS", 30, "v1")), true);
        }
    }

    private static void esperarInvalidacoes(int mO, int quantas) throws Exception {
        long fim = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < fim && origem.contar(mO, "respondeu") < quantas) Thread.sleep(25);
    }

    private static void grupoInvalidate() throws Exception {
        titulo("E) CASO 2 - invalidação ativa (INVALIDATE cifrado da Origem para os 3 Edges) e consistência");
        CanalTCP[] c = new CanalTCP[EDGES.length];
        try {
            for (int i = 0; i < EDGES.length; i++) c[i] = abrir(EDGES[i], "");
            for (int i = 0; i < EDGES.length; i++) {
                get(c[i], "/arquivo31.ext");
                get(c[i], "/arquivo32.ext");
            }
            int[] mE = new int[EDGES.length];
            for (int i = 0; i < EDGES.length; i++) mE[i] = edge[i].marca();
            int mO = origem.marca();
            origem.comando("invalidar arquivo31.ext");
            esperarInvalidacoes(mO, EDGES.length);
            for (int i = 0; i < EDGES.length; i++) {
                String p = "E0" + (i + 1);
                verificar(p + "a", "Edge " + EDGES[i] + ": log confirma INVALIDATE (HMAC validado) e arquivo31 removido do cache", "true",
                        String.valueOf(edge[i].contem(mE[i], "INVALIDATE da Origem (HMAC validado): /arquivo31.ext removido do cache")), true);
                verificar(p + "b", "Edge " + EDGES[i] + ": próxima GET /arquivo31.ext força MISS (mesma versão v1)", "true",
                        String.valueOf(ok200(get(c[i], "/arquivo31.ext"), "MISS", 31, "v1")), true);
                verificar(p + "c", "Edge " + EDGES[i] + ": /arquivo32.ext, que não foi invalidado, segue em HIT (seletivo)", "true",
                        String.valueOf(ok200(get(c[i], "/arquivo32.ext"), "HIT", 32, "v1")), true);
            }
            mO = origem.marca();
            origem.comando("atualizar arquivo32.ext");
            esperarInvalidacoes(mO, EDGES.length);
            for (int i = 0; i < EDGES.length; i++) {
                verificar("E1" + (i + 1), "Edge " + EDGES[i] + ": após atualizar+invalidar, GET /arquivo32.ext é MISS e traz a versão v2", "true",
                        String.valueOf(ok200(get(c[i], "/arquivo32.ext"), "MISS", 32, "v2")), true);
            }
            verificar("E14", "a requisição seguinte vira HIT já com a versão v2 (cache renovado com segurança)", "true",
                    String.valueOf(ok200(get(c[0], "/arquivo32.ext"), "HIT", 32, "v2")), true);
        } finally {
            for (CanalTCP x : c) if (x != null) x.close();
        }

        titulo("F) INVALIDATE só vale se vier da Origem");
        int mE = edge[0].marca();
        try (CanalTCP normal = abrir(EDGES[0], ""); CanalTCP intruso = abrir(EDGES[0], "");
             CanalTCP errado = abrir(EDGES[0], "ORIGEM:segredo-errado");
             CanalTCP daOrigem = abrir(EDGES[0], "ORIGEM:" + Config.SEGREDO_ORIGEM)) {
            get(normal, "/arquivo20.ext");
            verificar("F01", "cliente comum envia INVALIDATE -> 403 Forbidden", "403 Forbidden",
                    status(pedir(intruso, "INVALIDATE /arquivo20.ext")), true);
            verificar("F02", "credencial de Origem errada -> 403 Forbidden", "403 Forbidden",
                    status(pedir(errado, "INVALIDATE /arquivo20.ext")), true);
            verificar("F03", "log registra 'INVALIDATE NEGADO' duas vezes", "2",
                    String.valueOf(edge[0].contar(mE, "INVALIDATE NEGADO")), true);
            verificar("F04", "cache intacto: GET /arquivo20.ext segue HIT", "true",
                    String.valueOf(ok200(get(normal, "/arquivo20.ext"), "HIT", 20, "v1")), true);
            verificar("F05", "Origem autêntica, mas com nome inválido -> 400 Bad Request", "400 Bad Request",
                    status(pedir(daOrigem, "INVALIDATE /arquivoXYZ.ext")), true);
            verificar("F06", "Origem autêntica, nome válido -> 200 OK", "200 OK",
                    status(pedir(daOrigem, "INVALIDATE /arquivo20.ext")), true);
            verificar("F07", "e a próxima GET /arquivo20.ext volta a ser MISS", "true",
                    String.valueOf(ok200(get(normal, "/arquivo20.ext"), "MISS", 20, "v1")), true);
        }
    }

    private static void grupoInvalidos() throws Exception {
        titulo("G) CASO 3 - requisições inválidas/inexistentes: a Origem NÃO é consultada");
        String[][] ruins = {
                {"G01", "GET /arquivoXYZ.ext"}, {"G02", "GET /arquivo0.ext"}, {"G03", "GET /arquivo1001.ext"}, {"G04", "GET /arquivo01.ext"},
                {"G05", "GET /arquivo-1.ext"}, {"G06", "GET /ARQUIVO5.EXT"}, {"G07", "GET arquivo5.ext"}, {"G08", "GET /arquivo5"},
                {"G09", "GET /arquivo5.ext extra"}, {"G10", "GET "}, {"G11", "GET"}, {"G12", "POST /arquivo5.ext"},
                {"G13", "get /arquivo5.ext"}, {"G14", "FOO"}, {"G15", ""},
        };
        int mO = origem.marca(), mE = edge[0].marca();
        try (CanalTCP c = abrir(EDGES[0], "")) {
            for (String[] t : ruins) {
                Resp r = pedir(c, t[1]);
                boolean ok = r != null && r.status().equals("400 Bad Request")
                        && String.valueOf(r.cab().get("Erro")).equals(Config.MSG_ERRO_ENTRADA);
                verificar(t[0], "'" + t[1] + "' -> 400 + 'Erro na entrada de dados. Tente outra vez!'", "true", String.valueOf(ok), true);
            }
        }
        verificar("G16", "a Origem não registrou nenhuma linha durante os pedidos inválidos", "0", String.valueOf(origem.desde(mO).size()), true);
        verificar("G17", "o Edge registrou o erro de entrada para cada pedido", String.valueOf(ruins.length),
                String.valueOf(edge[0].contar(mE, "ERRO  Erro na entrada de dados")), true);

        Path f = Path.of("dados_origem", "arquivo999.ext"), bak = Path.of("dados_origem", "arquivo999.bak");
        Files.move(f, bak, StandardCopyOption.REPLACE_EXISTING);
        try (CanalTCP c = abrir(EDGES[0], "")) {
            int mO2 = origem.marca();
            Resp a = get(c, "/arquivo999.ext"), b = get(c, "/arquivo999.ext");
            verificar("G18", "nome válido mas inexistente na Origem -> 404 Not Found", "404 Not Found", status(a), true);
            verificar("G19", "o 404 não é guardado em cache (a Origem foi consultada nas 2 vezes)", "2",
                    String.valueOf(origem.contar(mO2, "Arquivo não encontrado")), true);
            verificar("G20", "a segunda resposta também é 404", "404 Not Found", status(b), true);
        } finally {
            Files.move(bak, f, StandardCopyOption.REPLACE_EXISTING);
        }
        try (CanalTCP c = abrir(EDGES[0], "")) {
            verificar("G21", "arquivo restaurado na Origem volta a ser servido", "true",
                    String.valueOf(ok200(get(c, "/arquivo999.ext"), "MISS", 999, "v1")), true);
        }
    }

    private static void grupoSeguranca() throws Exception {
        titulo("H) CASO 3 - erro de cifra na borda: o Edge ENCERRA a conexão, alerta no log e o cache fica intacto");
        try (CanalTCP prep = abrir(EDGES[0], "")) {
            get(prep, "/arquivo20.ext");
            get(prep, "/arquivo21.ext");
        }
        int mO = origem.marca(), mE = edge[0].marca();

        String hs;
        try {
            CanalTCP c = CanalTCP.conectar(Config.HOST, EDGES[0], "", true, 3000, MUDO);
            c.close();
            hs = "handshake concluído";
        } catch (Exception e) {
            hs = "<handshake recusado>";
        }
        verificar("H01", "handshake com cifra RSA corrompida (erro de decifragem) -> recusado", "<handshake recusado>", hs, true);
        String lixo;
        try (java.net.Socket s = new java.net.Socket(Config.HOST, EDGES[0])) {
            s.setSoTimeout(3000);
            s.getOutputStream().write("isto-nao-e-um-handshake\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            lixo = s.getInputStream().read() == -1 ? ENCERRADA : "<respondeu>";
        } catch (IOException e) {
            lixo = ENCERRADA;
        }
        verificar("H02", "lixo no lugar do handshake -> conexão encerrada", ENCERRADA, lixo, true);

        Adulteracao[] modos = {Adulteracao.MAC_CORROMPIDO, Adulteracao.CIFRADO_ADULTERADO, Adulteracao.HASH_SEM_CHAVE};
        String[] rot = {"HMAC corrompido", "texto cifrado adulterado", "hash SHA-256 sem chave"};
        for (int i = 0; i < modos.length; i++) {
            try (CanalTCP c = abrir(EDGES[0], "")) {
                c.enviarAdulterado("GET /arquivo20.ext", modos[i]);
                verificar("H0" + (i + 3), "GET com " + rot[i] + " -> o Edge encerra a conexão", ENCERRADA,
                        lerOuNull(c) == null ? ENCERRADA : "<respondeu>", true);
            }
        }
        try (CanalTCP c = abrir(EDGES[0], "")) {
            c.enviarDuplicado("GET /arquivo20.ext");
            verificar("H06", "replay: 1º pacote é respondido (HIT)", "true", String.valueOf(lerOuNull(c) != null), true);
            verificar("H07", "replay: 2º pacote idêntico -> conexão encerrada", ENCERRADA, lerOuNull(c) == null ? ENCERRADA : "<respondeu>", true);
        }
        Thread.sleep(300);
        verificar("H08", "log do Edge registra os alertas (handshake e HMAC)", "true",
                String.valueOf(edge[0].contar(mE, "ALERTA: handshake inválido") >= 2
                        && edge[0].contar(mE, "ALERTA: mensagem sem HMAC válido") >= 4), true);
        verificar("H09", "a Origem não recebeu nenhuma requisição durante os ataques", "0", String.valueOf(origem.contar(mO, "GET /")), true);
        try (CanalTCP c = abrir(EDGES[0], "")) {
            verificar("H10", "cache intacto: /arquivo20.ext e /arquivo21.ext seguem em HIT", "true",
                    String.valueOf(ok200(get(c, "/arquivo20.ext"), "HIT", 20, "v1") && ok200(get(c, "/arquivo21.ext"), "HIT", 21, "v1")), true);
        }
    }

    private static void grupoConcorrencia() throws Exception {
        titulo("I) GERADOR DE TRÁFEGO concorrente: 8 clientes x 12 requisições nos 3 Edges");
        AtomicInteger erros = new AtomicInteger(), feitas = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            final int semente = t;
            pool.submit(() -> {
                Random r = new Random(semente);
                try (CanalTCP c = abrir(EDGES[semente % EDGES.length], "")) {
                    for (int k = 0; k < 12; k++) {
                        int n = 200 + r.nextInt(15);
                        Resp resp = get(c, "/arquivo" + n + ".ext");
                        feitas.incrementAndGet();
                        if (resp == null || !resp.status().equals("200 OK") || resp.corpo().length() != 1024
                                || !resp.corpo().startsWith("[v1] Conteudo mestre do arquivo" + n + ".ext")) erros.incrementAndGet();
                    }
                } catch (Exception e) {
                    erros.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);
        verificar("I01", "96 requisições concorrentes: todas com conteúdo correto, sem erro", "96/0", feitas.get() + "/" + erros.get(), true);
    }

    private static void grupoResiliencia() throws Exception {
        titulo("J) RESILIÊNCIA: com a Origem FORA DO AR, a borda continua servindo o que está em cache");
        try (CanalTCP c = abrir(EDGES[2], "")) {
            get(c, "/arquivo60.ext");
            origem.parar();
            origem = null;
            Thread.sleep(300);
            verificar("J01", "Origem derrubada: GET /arquivo60.ext (em cache) continua HIT", "true",
                    String.valueOf(ok200(get(c, "/arquivo60.ext"), "HIT", 60, "v1")), true);
            verificar("J02", "GET de arquivo fora do cache -> 502 Bad Gateway (erro tratado, sem travar)", "502 Bad Gateway",
                    status(get(c, "/arquivo61.ext")), true);
            verificar("J03", "o Edge segue vivo e servindo o cache", "true",
                    String.valueOf(ok200(get(c, "/arquivo60.ext"), "HIT", 60, "v1")), true);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        verificarPortasLivres();
        Runtime.getRuntime().addShutdownHook(new Thread(TesteQ4::derrubar));
        System.out.println("Subindo Origem, 3 Edges e Router como processos independentes (TTL=" + TTL
                + " s, latência simulada desligada)...");
        subirTudo();
        grupoOrigem();
        grupoRouter();
        grupoHitMiss();
        grupoTtl();
        grupoInvalidate();
        grupoInvalidos();
        grupoSeguranca();
        grupoConcorrencia();
        grupoResiliencia();
        derrubar();
        System.out.printf("%n=== RESULTADO: %d/%d verificações passaram ===%n", passou, total);
        System.exit(passou == total ? 0 : 1);
    }
}
