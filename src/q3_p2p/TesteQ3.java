package q3_p2p;

import comum.Adulteracao;
import comum.CanalTCP;
import comum.Config;
import comum.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * QUESTÃO 3 — Teste automatizado do anel P2P com informações VÁLIDAS e INVÁLIDAS.
 *
 * Sobe os 6 nós como PROCESSOS independentes (um java NoP2P por nó), digita os comandos no console de cada um
 * e confere, pela saída de TODOS os nós, o que aconteceu (e o que NÃO aconteceu, como propagar pacotes).
 *
 * Uso: java q3_p2p.TesteQ3 [caso1 | caso2 | caso3 | completo]     (padrão: completo)
 *      Código de saída 0 somente se todas as verificações passarem.
 */
public class TesteQ3 {
    private static final int N = Config.P2P_TAMANHO_ANEL;
    private static final No[] nos = new No[N];
    private static int total, passou;

    // ------------------------------------------------------------------ processo de um nó

    private static final class No {
        final int id;
        final Process proc;
        final BufferedWriter entrada;
        final List<String> saida = new ArrayList<>();

        No(int id, String... extra) throws IOException {
            this.id = id;
            List<String> cmd = new ArrayList<>(List.of(
                    System.getProperty("java.home") + File.separator + "bin" + File.separator + "java",
                    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                    "-cp", caminhoDasClasses(), NoP2P.class.getName(), String.valueOf(id)));   // funciona com qualquer nome de pacote
            cmd.addAll(Arrays.asList(extra));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            entrada = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            Thread leitor = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) synchronized (saida) { saida.add(l); }
                } catch (IOException ignorado) { /* processo encerrado */ }
            }, "leitor-P" + id);
            leitor.setDaemon(true);
            leitor.start();
        }

        void comando(String linha) throws IOException { entrada.write(linha + "\n"); entrada.flush(); }

        int marca() { synchronized (saida) { return saida.size(); } }

        List<String> desde(int marca) {
            synchronized (saida) { return new ArrayList<>(saida.subList(Math.min(marca, saida.size()), saida.size())); }
        }

        boolean contem(int marca, String trecho) {
            for (String l : desde(marca)) if (l.contains(trecho)) return true;
            return false;
        }

        boolean aguardar(String trecho, int marca, long ms) throws InterruptedException {
            long fim = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < fim) {
                if (contem(marca, trecho)) return true;
                Thread.sleep(25);
            }
            return false;
        }

        void parar() {
            proc.destroy();
            try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    /**
     * Onde estão as classes compiladas, para o processo-filho achá-las.
     * Em projeto com module-info.java (Eclipse modular) o classpath do teste é VAZIO e as classes vêm do module-path,
     * então usamos a origem do próprio código (pasta bin/out ou jar) e, por garantia, classpath e module-path.
     */
    private static String caminhoDasClasses() {
        List<String> partes = new ArrayList<>();
        try {
            var origem = NoP2P.class.getProtectionDomain().getCodeSource();
            if (origem != null) partes.add(new File(origem.getLocation().toURI()).getPath());
        } catch (Exception ignorado) { /* cai nas propriedades abaixo */ }
        for (String chave : new String[]{"java.class.path", "jdk.module.path"}) {
            String v = System.getProperty(chave);
            if (v == null || v.isBlank()) continue;
            for (String e : v.split(File.pathSeparator)) if (!e.isBlank() && !partes.contains(e)) partes.add(e);
        }
        return String.join(File.pathSeparator, partes);
    }

    /** Antes de subir os nós, confere se as portas 9100-9105 estão livres. */
    private static void verificarPortasLivres() {
        List<String> ocupadas = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            int porta = Config.P2P_PORTA_BASE + i;
            try {
                new ServerSocket(porta).close();        // consegue abrir e fechar: a porta está livre
            } catch (IOException e) {
                ocupadas.add("TCP " + porta + " (P" + i + ")");
            }
        }
        if (ocupadas.isEmpty()) return;
        System.err.println("ERRO: portas já em uso: " + String.join(", ", ocupadas));
        System.err.println("\nHá processos antigos ainda rodando e usando essas portas. Encerre-os e rode o teste de novo:");
        System.err.println("  - Eclipse: na view Debug/Console, botão vermelho (Terminate) em cada execução antiga");
        System.err.println("             (ou 'Terminate/Disconnect All'). Execuções interrompidas podem deixar processos-filho órfãos.");
        System.err.println("  - Linux/macOS: 'jps -l' lista os processos Java; depois 'kill <PID>'");
        System.err.println("                 ou, para uma porta: 'lsof -ti :PORTA | xargs kill'");
        System.err.println("  - Windows:     'netstat -ano | findstr :PORTA' e depois 'taskkill /PID <PID> /F'");
        System.exit(2);
    }

    private static void derrubar() {
        for (int i = 0; i < N; i++) if (nos[i] != null) { nos[i].parar(); nos[i] = null; }
    }

    /** Sobe os 6 nós. Se adulterado >= 0, esse nó corrompe o que repassa (flag = --adulterar ou --adulterar-hmac). */
    private static void subirAnel(int adulterado, String flag) throws Exception {
        derrubar();
        for (int i = 0; i < N; i++) nos[i] = (i == adulterado) ? new No(i, flag) : new No(i);
        for (No n : nos) {
            long fim = System.currentTimeMillis() + 20_000;
            while (!n.contem(0, "ativo na porta")) {
                if (!n.proc.isAlive() || System.currentTimeMillis() > fim) {
                    Thread.sleep(200);   // dá tempo de o leitor capturar a saída do processo
                    throw new IllegalStateException("nó P" + n.id + " não subiu. Saída do processo:\n"
                            + String.join("\n", n.desde(0))
                            + "\n(Dica: porta " + (Config.P2P_PORTA_BASE + n.id) + " em uso por outro processo? "
                            + "Encerre os nós que estiverem rodando e tente de novo.)");
                }
                Thread.sleep(50);
            }
        }
        Thread.sleep(300);
    }

    // ------------------------------------------------------------------ utilidades de verificação

    private static void verificar(String id, String descricao, String esperado, String obtido, boolean imprimir) {
        total++;
        boolean ok = esperado.equals(obtido);
        if (ok) passou++;
        if (imprimir || !ok) {
            System.out.printf("%-6s %-66s esperado=%-22s obtido=%-22s %s%n", id, cortar(descricao, 66),
                    cortar(esperado, 22), cortar(obtido, 22), ok ? "OK" : "<<< FALHOU");
        }
    }

    private static void verificar(String id, String descricao, boolean condicao) {
        verificar(id, descricao, "true", String.valueOf(condicao), true);
    }

    private static String cortar(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 3) + "..."; }

    private static int[] marcas() {
        int[] m = new int[N];
        for (int i = 0; i < N; i++) m[i] = nos[i].marca();
        return m;
    }

    /** Quantos nós (exceto 'exceto') registraram alguma linha nova desde as marcas. */
    private static List<Integer> nosComAtividade(int[] marcas, int... exceto) {
        List<Integer> ativos = new ArrayList<>();
        outer:
        for (int i = 0; i < N; i++) {
            for (int e : exceto) if (e == i) continue outer;
            if (!nos[i].desde(marcas[i]).isEmpty()) ativos.add(i);
        }
        return ativos;
    }

    private static void mostrar(String titulo, int[] marcas, int... ids) {
        System.out.println("   ---- " + titulo + " ----");
        for (int i : ids) for (String l : nos[i].desde(marcas[i])) {
            if (l.contains("Handshake: chaves")) continue;
            System.out.println("   " + cortar(l, 205));
        }
    }

    private static void titulo(String t) { System.out.println("\n################ " + t + " ################"); }

    private static String resultado(int responsavel, int arquivo, int saltos) {
        return "-> P" + responsavel + ": conteudo-simulado-de-arquivo" + arquivo + ".ext@P" + responsavel + " [" + saltos + " salto(s)]";
    }

    /** Envia "buscar arquivo" ao nó 'origem' e devolve o texto de RESULTADO (ou "<sem resultado>"). */
    private static String buscar(int origem, String arquivo) throws Exception {
        int m = nos[origem].marca();
        nos[origem].comando("buscar " + arquivo);
        String chave = "RESULTADO da busca de " + arquivo + " ";
        if (!nos[origem].aguardar(chave, m, 7000)) return "<sem resultado>";
        for (String l : nos[origem].desde(m)) {
            int i = l.indexOf(chave);
            if (i >= 0) return l.substring(i + chave.length());
        }
        return "<sem resultado>";
    }

    // ------------------------------------------------------------------ CASO 1

    private static void caso1() throws Exception {
        titulo("CASO 1 - busca válida com criptografia salto a salto (P0, arquivo35.ext)");
        subirAnel(-1, null);
        int[] m = marcas();
        String r = buscar(0, "arquivo35.ext");
        Thread.sleep(500);
        mostrar("P0 (origem)", m, 0);
        mostrar("P1", m, 1);
        mostrar("P2", m, 2);
        mostrar("P3 (responsável)", m, 3);
        System.out.println();
        verificar("C1-01", "P0 recebe a resposta de P3 após 3 saltos", resultado(3, 35, 3), r, true);
        verificar("C1-02", "enlace P0-P1: canal seguro (RSA -> AES+HMAC) aberto por P0", nos[0].contem(m[0], "Estabelecendo canal seguro P0 -> P1"));
        verificar("C1-03", "P1 decifra e valida o HMAC do que P0 enviou", nos[1].contem(m[1], "[HMAC OK] BUSCA|0|arquivo35.ext|1"));
        verificar("C1-04", "P1 não é responsável e repassa a P2 em NOVO canal seguro", nos[1].contem(m[1], "repassando ao sucessor P2") && nos[1].contem(m[1], "Estabelecendo canal seguro P1 -> P2"));
        verificar("C1-05", "P2 decifra, valida o HMAC e repassa a P3", nos[2].contem(m[2], "[HMAC OK] BUSCA|0|arquivo35.ext|2") && nos[2].contem(m[2], "Estabelecendo canal seguro P2 -> P3"));
        verificar("C1-06", "P3 valida o HMAC e se reconhece responsável (faixa 31-40)", nos[3].contem(m[3], "[HMAC OK] BUSCA|0|arquivo35.ext|3") && nos[3].contem(m[3], "faixa 31-40"));
        verificar("C1-07", "P3 responde à origem P0 por canal seguro cifrado", nos[3].contem(m[3], "Estabelecendo canal seguro P3 -> P0") && nos[3].contem(m[3], "[AES-CBC+HMAC] RESP|3|arquivo35.ext"));
        verificar("C1-08", "P0 valida o HMAC da resposta", nos[0].contem(m[0], "[HMAC OK] RESP|3|arquivo35.ext"));
        verificar("C1-09", "P4 e P5 não participaram (nenhuma linha nova)", nosComAtividade(m, 0, 1, 2, 3).isEmpty());
        verificar("C1-10", "log registra tráfego cifrado (pacote=sid.dir.seq...) em P1", nos[1].contem(m[1], "pacote="));
    }

    // ------------------------------------------------------------------ CASO 2

    /** Envia um comando inválido ao nó e prova que NADA foi propagado nem aberto. */
    private static void entradaInvalida(String id, int origem, String comando) throws Exception {
        int[] m = marcas();
        nos[origem].comando(comando);
        Thread.sleep(900);
        List<String> novas = nos[origem].desde(m[origem]);
        String joined = String.join("\n", novas);
        boolean erro = joined.contains(Config.MSG_ERRO_ENTRADA);
        boolean semRede = !joined.contains("Estabelecendo canal") && !joined.contains("ENVIADO") && !joined.contains("Handshake");
        boolean outrosQuietos = nosComAtividade(m, origem).isEmpty();
        verificar(id, "P" + origem + ": '" + comando + "' -> erro impresso, sem rede, anel quieto",
                "true", String.valueOf(erro && semRede && outrosQuietos), true);
    }

    private static void caso2() throws Exception {
        titulo("CASO 2 - entrada inválida (a rejeição ocorre antes de criptografia ou conexão)");
        subirAnel(-1, null);
        int[] m = marcas();
        nos[1].comando("buscar arquivo99.ext");
        nos[4].comando("buscar arquivoABC.ext");
        Thread.sleep(1200);
        mostrar("P1 (arquivo99.ext)", m, 1);
        mostrar("P4 (arquivoABC.ext)", m, 4);
        System.out.println();
        verificar("C2-01", "P1 imprime 'Erro na entrada de dados. Tente outra vez!'", nos[1].contem(m[1], Config.MSG_ERRO_ENTRADA));
        verificar("C2-02", "P4 imprime 'Erro na entrada de dados. Tente outra vez!'", nos[4].contem(m[4], Config.MSG_ERRO_ENTRADA));
        verificar("C2-03", "P1 e P4 não abriram conexão nem fizeram criptografia",
                !nos[1].contem(m[1], "Estabelecendo canal") && !nos[1].contem(m[1], "ENVIADO")
                        && !nos[4].contem(m[4], "Estabelecendo canal") && !nos[4].contem(m[4], "ENVIADO"));
        verificar("C2-04", "nenhum pacote foi propagado: P0, P2, P3, P5 sem nenhuma linha nova", nosComAtividade(m, 1, 4).isEmpty());
    }

    // ------------------------------------------------------------------ CASO 3

    private static void caso3(String flag, String descricao, boolean detalhar) throws Exception {
        titulo("CASO 3 - falha de integridade: " + descricao + " (P4 -> P5)");
        subirAnel(4, flag);
        int[] m = marcas();
        nos[4].comando("buscar arquivo5.ext");
        boolean timeout = nos[4].aguardar("Sem resposta em 5 s", m[4], 9000);
        Thread.sleep(500);
        if (detalhar) {
            mostrar("P4 (origem, corrompe o que envia)", m, 4);
            mostrar("P5 (detecta e descarta)", m, 5);
            mostrar("P0 (não deve receber nada)", m, 0);
            System.out.println();
        }
        String t = descricao.substring(0, Math.min(14, descricao.length()));
        verificar("C3-01", t + ": P5 detecta a falha de autenticação/integridade e grava no log",
                nos[5].contem(m[5], "FALHA DE AUTENTICA") && nos[5].contem(m[5], "HMAC inv"));
        verificar("C3-02", t + ": P5 descarta o pacote e interrompe o repasse", nos[5].contem(m[5], "DESCARTADO") && nos[5].contem(m[5], "INTERROMPIDO"));
        verificar("C3-03", t + ": P5 NÃO repassou a P0 (nenhum 'repassando')", !nos[5].contem(m[5], "repassando ao sucessor"));
        verificar("C3-04", t + ": P0, P1, P2 e P3 sem nenhuma linha nova", nosComAtividade(m, 4, 5).isEmpty());
        verificar("C3-05", t + ": a origem P4 fica sem resposta (timeout tratado)", timeout);
        // o anel continua útil: P5 busca um arquivo de P0 e P4 busca um arquivo próprio
        verificar("C3-06", t + ": P5 ainda busca arquivo3.ext em P0 (1 salto)", resultado(0, 3, 1), buscar(5, "arquivo3.ext"), detalhar);
        verificar("C3-07", t + ": P4 ainda acha arquivo45.ext localmente (0 salto)", resultado(4, 45, 0), buscar(4, "arquivo45.ext"), detalhar);
    }

    // ------------------------------------------------------------------ MATRIZES

    private static void matrizValidos() throws Exception {
        titulo("A) BUSCAS VÁLIDAS: os 60 arquivos a partir de P0, e as pontas de cada faixa a partir de cada nó");
        subirAnel(-1, null);
        int certos = 0;
        for (int a = 1; a <= 60; a++) {
            int resp = (a - 1) / 10;
            String r = buscar(0, "arquivo" + a + ".ext");
            String esp = resultado(resp, a, resp % N);
            if (r.equals(esp)) certos++;
            verificar("V-P0", "arquivo" + a + ".ext a partir de P0", esp, r, false);
        }
        System.out.printf("   P0 -> 60 arquivos: %d/60 achados pelo nó certo, com o nº de saltos esperado%n", certos);
        for (int o = 1; o < N; o++) {
            int ok = 0, tot = 0;
            for (int r = 0; r < N; r++) {
                for (int a : new int[]{r * 10 + 1, r * 10 + 10}) {
                    tot++;
                    int saltos = ((r - o) % N + N) % N;
                    String esp = resultado(r, a, saltos);
                    String obt = buscar(o, "arquivo" + a + ".ext");
                    if (obt.equals(esp)) ok++;
                    verificar("V-P" + o, "arquivo" + a + ".ext a partir de P" + o, esp, obt, false);
                }
            }
            System.out.printf("   P%d -> primeiro e último arquivo de cada faixa: %d/%d (saltos = (responsável - origem) mod 6)%n", o, ok, tot);
        }
        verificar("V-LOC", "P2 acha arquivo25.ext localmente, sem rede (0 salto)", resultado(2, 25, 0), buscar(2, "arquivo25.ext"), true);
    }

    private static void matrizInvalidos() throws Exception {
        titulo("B) ENTRADAS INVÁLIDAS no console dos nós (anel todo ligado: nada pode trafegar)");
        subirAnel(-1, null);
        String[][] casos = {
                {"I01", "1", "buscar arquivo99.ext"},
                {"I02", "4", "buscar arquivoABC.ext"},
                {"I03", "0", "buscar arquivo0.ext"},
                {"I04", "2", "buscar arquivo61.ext"},
                {"I05", "3", "buscar arquivo05.ext"},
                {"I06", "5", "buscar arquivo-1.ext"},
                {"I07", "1", "buscar arquivo5"},
                {"I08", "2", "buscar ARQUIVO5.EXT"},
                {"I09", "4", "buscar arquivo5.ext; DROP"},
                {"I10", "0", "buscar"},
                {"I11", "3", "procurar arquivo5.ext"},
                {"I12", "5", ""},
        };
        for (String[] c : casos) entradaInvalida(c[0], Integer.parseInt(c[1]), c[2]);
        verificar("I13", "depois dos erros, P1 ainda busca arquivo35.ext (2 saltos)", resultado(3, 35, 2), buscar(1, "arquivo35.ext"), true);
    }

    /** Mensagens malformadas vindas de um par autenticado (protocolo): devem ser ignoradas e jamais repassadas. */
    private static void protocolo() throws Exception {
        titulo("C) MENSAGENS MALFORMADAS no protocolo (par autenticado): IGNORADAS, sem repasse e sem derrubar o nó");
        subirAnel(-1, null);
        String[][] msgs = {
                {"P01", "BUSCA|99|arquivo5.ext|0"}, {"P02", "BUSCA|70000|arquivo5.ext|0"}, {"P03", "BUSCA|-1|arquivo5.ext|0"},
                {"P04", "BUSCA|abc|arquivo5.ext|0"}, {"P05", "BUSCA|0|arquivo99.ext|0"}, {"P06", "BUSCA|0|arquivoABC.ext|0"},
                {"P07", "BUSCA|0|arquivo5.ext|-1"}, {"P08", "BUSCA|0|arquivo5.ext|99"}, {"P09", "BUSCA|0|arquivo5.ext|x"},
                {"P10", "BUSCA|0|arquivo5.ext"}, {"P11", "BUSCA|0|arquivo5.ext|0|extra"}, {"P12", "RESP|0|arquivo5.ext|x|1"},
                {"P13", "FOO|1|2"}, {"P14", ""},
        };
        for (String[] t : msgs) {
            int[] m = marcas();
            try (CanalTCP c = CanalTCP.conectar(Config.HOST, Config.P2P_PORTA_BASE + 2, "teste", false, 3000, Log.mudo())) {
                c.enviar(t[1]);
                Thread.sleep(700);
            }
            boolean ignorada = nos[2].contem(m[2], "IGNORADA");
            boolean semRepasse = !nos[2].contem(m[2], "repassando") && !nos[2].contem(m[2], "Estabelecendo canal")
                    && nosComAtividade(m, 2).isEmpty();
            verificar(t[0], "P2 recebe '" + t[1] + "' -> ignorada, sem repasse", "true", String.valueOf(ignorada && semRepasse), true);
        }
        verificar("P15", "P2 segue funcionando: busca arquivo35.ext (1 salto)", resultado(3, 35, 1), buscar(2, "arquivo35.ext"), true);

        // pacotes adulterados enviados direto a P2: detectados, descartados, e a busca NÃO chega a P3
        Adulteracao[] modos = {Adulteracao.MAC_CORROMPIDO, Adulteracao.CIFRADO_ADULTERADO, Adulteracao.HASH_SEM_CHAVE};
        String[] rot = {"HMAC corrompido", "texto cifrado adulterado", "SHA-256 sem chave"};
        for (int i = 0; i < modos.length; i++) {
            int[] m = marcas();
            try (CanalTCP c = CanalTCP.conectar(Config.HOST, Config.P2P_PORTA_BASE + 2, "teste", false, 3000, Log.mudo())) {
                c.enviarAdulterado("BUSCA|0|arquivo35.ext|1", modos[i]);
                Thread.sleep(700);
            }
            verificar("P" + (16 + i), "P2 recebe BUSCA com " + rot[i] + " -> detecta, descarta, P3 quieto", "true",
                    String.valueOf(nos[2].contem(m[2], "FALHA DE AUTENTICA") && nos[2].contem(m[2], "DESCARTADO")
                            && nosComAtividade(m, 2).isEmpty()), true);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String cenario = args.length > 0 ? args[0] : "completo";
        verificarPortasLivres();
        Runtime.getRuntime().addShutdownHook(new Thread(TesteQ3::derrubar));
        switch (cenario) {
            case "caso1" -> caso1();
            case "caso2" -> caso2();
            case "caso3" -> {
                caso3("--adulterar", "texto cifrado alterado no canal", true);
                caso3("--adulterar-hmac", "HMAC inválido", true);
            }
            case "completo" -> {
                caso1();
                caso2();
                matrizValidos();
                matrizInvalidos();
                protocolo();
                caso3("--adulterar", "texto cifrado alterado no canal", false);
                caso3("--adulterar-hmac", "HMAC inválido", false);
            }
            default -> {
                System.out.println("Uso: java q3_p2p.TesteQ3 [caso1 | caso2 | caso3 | completo]");
                return;
            }
        }
        derrubar();
        System.out.printf("%n=== RESULTADO: %d/%d verificações passaram ===%n", passou, total);
        System.exit(passou == total ? 0 : 1);
    }
}
