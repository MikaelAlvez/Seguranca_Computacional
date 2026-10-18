package q3_p2p;

import comum.Adulteracao;
import comum.CanalSeguro;
import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;
import comum.Validacao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * QUESTÃO 3 — Nomeação em P2P: anel de 6 nós (P0..P5), sem servidor central.
 *
 * Cada nó é um PROCESSO independente (multithread) com:
 *   - identificador numérico (0..5) e porta TCP = base + id;
 *   - sucessor = (id+1) % 6 e antecessor = (id+5) % 6 (o anel é percorrido no sentido horário: Pi -> Pi+1);
 *   - os arquivos da sua faixa: Pi guarda os arquivos 10*i+1 .. 10*i+10 (60 arquivos no total).
 *
 * Busca: o nó de origem VALIDA o comando antes de qualquer criptografia ou conexão e repassa BUSCA ao sucessor,
 * salto a salto, até o responsável, que responde DIRETO à origem. CADA ENLACE (cada salto) abre seu próprio canal
 * seguro: handshake RSA -> chaves AES + HMAC novas; o nó seguinte decifra, valida o HMAC e RECIFRA para o próximo.
 *
 * Defesas: todo campo recebido (origem, arquivo, saltos) é validado em CADA salto; mensagem inválida é IGNORADA
 * e registrada, sem propagação. Pacote com HMAC/cifra inválidos é descartado e o repasse é interrompido.
 *
 * Uso: java q3_p2p.NoP2P <id 0..5> [--adulterar | --adulterar-hmac] [--atraso ms] [--sair] [--buscar arquivoN.ext]...
 *      (sem --buscar o nó aceita comandos no console: "buscar arquivoN.ext" / "sair";
 *       com --sair o processo encerra depois de executar as buscas dadas na linha de comando;
 *       --adulterar / --adulterar-hmac: o nó corrompe (texto cifrado / HMAC) o que repassa — teste de integridade)
 */
public class NoP2P {
    private static final int TOTAL_ARQUIVOS = Config.P2P_TAMANHO_ANEL * Config.P2P_ARQUIVOS_POR_NO;

    private final int id;
    private final int sucessor;
    private final int antecessor;
    private final Adulteracao adulteracao;          // null = nó íntegro
    private final Log log;
    private final KeyPair par = CanalSeguro.gerarParRSA();
    private final Set<String> meusArquivos = new HashSet<>();   // só leitura depois do construtor
    /** Buscas iniciadas por este nó e ainda sem resposta. */
    private final Map<String, CompletableFuture<String>> pendentes = new ConcurrentHashMap<>();

    public NoP2P(int id, Adulteracao adulteracao) {
        this.id = id;
        this.sucessor = (id + 1) % Config.P2P_TAMANHO_ANEL;
        this.antecessor = (id + Config.P2P_TAMANHO_ANEL - 1) % Config.P2P_TAMANHO_ANEL;
        this.adulteracao = adulteracao;
        this.log = new Log("p2p-P" + id);
        for (int n = id * Config.P2P_ARQUIVOS_POR_NO + 1; n <= (id + 1) * Config.P2P_ARQUIVOS_POR_NO; n++) {
            meusArquivos.add("arquivo" + n + ".ext");
        }
    }

    private int porta(int destino) { return Config.P2P_PORTA_BASE + destino; }

    // ------------------------------------------------------------------ servidor (recebe dos vizinhos)

    public void iniciar() throws IOException {
        ServerSocket servidor = new ServerSocket(porta(id));
        log.info("Nó P" + id + " ativo na porta " + porta(id) + " | sucessor=P" + sucessor + " antecessor=P" + antecessor
                + " | faixa de arquivos: arquivo" + (id * 10 + 1) + ".ext a arquivo" + ((id + 1) * 10) + ".ext"
                + (adulteracao != null ? " | MODO ADULTERAR (" + adulteracao + "): corrompe o que repassa" : ""));
        Thread aceitar = new Thread(() -> {
            while (true) {
                try {
                    Socket s = servidor.accept();
                    new Thread(() -> atenderEnlace(s), "enlace-" + s.getPort()).start();
                } catch (IOException e) {
                    log.erro("Falha ao aceitar conexão: " + e.getMessage());
                    return;
                }
            }
        }, "aceitador");
        aceitar.start();
    }

    /** Uma thread por enlace de entrada: lê mensagens cifradas de um vizinho. */
    private void atenderEnlace(Socket s) {
        try (CanalTCP canal = CanalTCP.aceitar(s, par, 5000, log, null)) {
            while (true) {
                String msg;
                try {
                    msg = canal.receber();
                } catch (ErroIntegridadeException e) {
                    log.seguranca("FALHA DE AUTENTICAÇÃO/INTEGRIDADE no enlace " + canal.peer() + ": " + e.getMessage()
                            + " -> pacote DESCARTADO; repasse INTERROMPIDO (nada é propagado pelo anel)");
                    continue;                                // descarta só o pacote ruim e segue escutando
                }
                if (msg == null) break;
                tratar(msg);
            }
        } catch (ErroIntegridadeException e) {
            log.seguranca("Handshake de enlace recusado: " + e.getMessage());
        } catch (IOException e) {
            log.info("Enlace encerrado (" + e.getMessage() + ")");
        }
    }

    private static int validarNo(int valor) {
        if (valor < 0 || valor >= Config.P2P_TAMANHO_ANEL) throw new IllegalArgumentException("nó fora do anel: " + valor);
        return valor;
    }

    private static String validarArquivo(String nome) {
        if (Validacao.numeroArquivo(nome, TOTAL_ARQUIVOS) < 0) throw new IllegalArgumentException("arquivo inválido: " + nome);
        return nome;
    }

    /** Valida TODOS os campos antes de agir: mensagem fora do padrão é ignorada, nunca repassada. */
    private void tratar(String msg) {
        String[] p = msg.split("\\|", -1);
        try {
            switch (p[0]) {
                case "BUSCA" -> {
                    if (p.length != 4) throw new IllegalArgumentException("BUSCA exige 4 campos");
                    int origem = validarNo(Integer.parseInt(p[1]));
                    String arquivo = validarArquivo(p[2]);
                    int saltos = Integer.parseInt(p[3]);
                    if (saltos < 0 || saltos > Config.P2P_TAMANHO_ANEL) throw new IllegalArgumentException("saltos fora do intervalo: " + saltos);
                    processarBusca(origem, arquivo, saltos);
                }
                case "RESP" -> {
                    if (p.length != 5) throw new IllegalArgumentException("RESP exige 5 campos");
                    int responsavel = validarNo(Integer.parseInt(p[1]));
                    String arquivo = validarArquivo(p[2]);
                    int saltos = Integer.parseInt(p[4]);
                    CompletableFuture<String> f = pendentes.get(arquivo);
                    if (f == null) throw new IllegalArgumentException("resposta não solicitada para " + arquivo);
                    log.info(">>> RESPOSTA recebida de P" + responsavel + " para " + arquivo + ": " + p[3]);
                    f.complete("P" + responsavel + ": " + p[3] + " [" + saltos + " salto(s)]");
                }
                default -> throw new IllegalArgumentException("tipo de mensagem desconhecido");
            }
        } catch (IllegalArgumentException e) {          // inclui NumberFormatException
            log.seguranca("Mensagem inválida IGNORADA, sem repasse (" + e.getMessage() + "): " + Log.resumo(msg, 60));
        } catch (RuntimeException e) {                  // nenhuma mensagem remota pode derrubar a thread do enlace
            log.erro("Erro inesperado ao tratar a mensagem (ignorada): " + e);
        }
    }

    // ------------------------------------------------------------------ busca

    /** @return false se o envio ao próximo nó falhou (nó inacessível). */
    private boolean processarBusca(int origem, String arquivo, int saltos) {
        if (meusArquivos.contains(arquivo)) {
            log.info("P" + id + " é o RESPONSÁVEL por " + arquivo + " (faixa " + (id * 10 + 1) + "-" + ((id + 1) * 10)
                    + ") após " + saltos + " salto(s). Respondendo à origem P" + origem);
            return responder(origem, "RESP|" + id + "|" + arquivo + "|conteudo-simulado-de-" + arquivo + "@P" + id + "|" + saltos);
        } else if (saltos >= Config.P2P_TAMANHO_ANEL) {
            log.erro("Busca de " + arquivo + " deu a volta no anel sem achar responsável");
            return responder(origem, "RESP|" + id + "|" + arquivo + "|NAO_ENCONTRADO|" + saltos);
        } else {
            log.info("P" + id + " NÃO é responsável por " + arquivo + " (minha faixa: " + (id * 10 + 1) + "-"
                    + ((id + 1) * 10) + ") -> repassando ao sucessor P" + sucessor + " (salto " + (saltos + 1) + ")");
            return enviarPara(sucessor, "BUSCA|" + origem + "|" + arquivo + "|" + (saltos + 1), adulteracao);
        }
    }

    private boolean responder(int origem, String resp) {
        if (origem == id) {                                  // a origem é o próprio responsável: sem rede
            tratar(resp);
            return true;
        }
        return enviarPara(origem, resp, null);
    }

    /** Cada salto abre um canal seguro NOVO (handshake RSA -> AES+HMAC), envia e fecha. */
    private boolean enviarPara(int destino, String texto, Adulteracao modo) {
        log.info("Estabelecendo canal seguro P" + id + " -> P" + destino + " (handshake RSA, chaves AES+HMAC)");
        try (CanalTCP canal = CanalTCP.conectar(Config.HOST, porta(destino), "P" + id, false, 5000, log)) {
            if (modo != null) canal.enviarAdulterado(texto, modo);
            else canal.enviar(texto);
            return true;
        } catch (IOException | ErroIntegridadeException e) {
            log.erro("Falha ao enviar para P" + destino + ": " + e.getMessage()
                    + " (o nó P" + destino + " está fora do ar? Inicie todos os nós do anel.)");
            return false;
        }
    }

    /** Inicia uma busca neste nó: valida ANTES de abrir qualquer conexão ou fazer criptografia. */
    public void buscar(String arquivo) {
        if (Validacao.numeroArquivo(arquivo, TOTAL_ARQUIVOS) < 0) {
            log.erro(Config.MSG_ERRO_ENTRADA + " (busca por '" + arquivo + "' rejeitada; nenhum pacote foi propagado pelo anel)");
            return;
        }
        log.info("BUSCA iniciada em P" + id + ": " + arquivo);
        CompletableFuture<String> resultado = new CompletableFuture<>();
        pendentes.put(arquivo, resultado);
        if (!processarBusca(id, arquivo, 0)) {               // o primeiro salto falhou: não há por que esperar
            pendentes.remove(arquivo);
            log.erro("Busca de " + arquivo + " NÃO realizada: o próximo nó do caminho está inacessível. "
                    + "Inicie os demais nós do anel (P0 a P5) e tente de novo.");
            return;
        }
        try {
            log.info("RESULTADO da busca de " + arquivo + " -> " + resultado.get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            log.erro("Sem resposta em 5 s para " + arquivo + ": a busca não voltou (algum nó do anel está inacessível ou o pacote foi descartado por falha de integridade).");
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
        } finally {
            pendentes.remove(arquivo);
        }
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java q3_p2p.NoP2P <id 0..5> [--adulterar | --adulterar-hmac] [--atraso ms] [--sair] [--buscar arquivoN.ext]...");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id < 0 || id >= Config.P2P_TAMANHO_ANEL) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (o id do nó deve estar entre 0 e " + (Config.P2P_TAMANHO_ANEL - 1) + ")");
            return;
        }
        Adulteracao modo = null;
        boolean sair = false;
        long atraso = 1500;
        List<String> buscas = new ArrayList<>();
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--adulterar" -> modo = Adulteracao.CIFRADO_ADULTERADO;
                    case "--adulterar-hmac" -> modo = Adulteracao.MAC_CORROMPIDO;
                    case "--sair" -> sair = true;
                    case "--atraso" -> atraso = Long.parseLong(args[++i]);
                    case "--buscar" -> buscas.add(args[++i]);
                    default -> { System.out.println(Config.MSG_ERRO_ENTRADA + " (opção: " + args[i] + ")"); return; }
                }
            }
        } catch (RuntimeException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA);
            return;
        }

        NoP2P no = new NoP2P(id, modo);
        try {
            no.iniciar();
        } catch (IOException e) {
            System.out.println("Não foi possível iniciar o nó P" + id + ": " + e.getMessage());
            return;
        }

        if (!buscas.isEmpty()) {
            try { Thread.sleep(atraso); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            for (String b : buscas) no.buscar(b);
            if (sair) System.exit(0);       // modo "roteiro": encerra o processo depois das buscas
        } else {
            Scanner teclado = new Scanner(System.in);
            System.out.println("Comandos: 'buscar arquivoN.ext' | 'sair'");
            while (teclado.hasNextLine()) {
                String linha = teclado.nextLine().trim();
                if (linha.equalsIgnoreCase("sair")) { System.exit(0); }
                String[] p = linha.split("\\s+");
                if (p.length == 2 && p[0].equalsIgnoreCase("buscar")) no.buscar(p[1]);
                else System.out.println(Config.MSG_ERRO_ENTRADA);
            }
        }
    }
}
