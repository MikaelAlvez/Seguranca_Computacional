package q1_dns;

import comum.Adulteracao;
import comum.ClienteUDPSeguro;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * QUESTÃO 1 — Cliente do Mini-DNS (serve para C1, C2 e C3).
 *
 * Uma thread "escuta" lê TODOS os datagramas que chegam: NOTIFY atualiza a tabela local;
 * respostas de consulta vão para uma BlockingQueue de onde a thread principal as retira.
 *
 * Uso:  java q1_dns.ClienteDNS <id> [--assinar] [--consultar nome] [--consultar-hmac-corrompido nome]
 *                                   [--ouvir segundos] [--interativo]
 */
public class ClienteDNS {
    private final Log log;
    private final ClienteUDPSeguro udp;
    private final Map<String, String> tabelaLocal = new ConcurrentHashMap<>();
    private final BlockingQueue<String> respostas = new LinkedBlockingQueue<>();
    private volatile boolean ativo = true;

    public ClienteDNS(String id) throws SocketException {
        this.log = new Log("dns-cliente-" + id);
        this.udp = new ClienteUDPSeguro(new InetSocketAddress(Config.HOST, Config.DNS_PORTA), 0, log);
    }

    public void conectar(boolean corromperHandshake) throws IOException, ErroIntegridadeException {
        udp.handshake("", corromperHandshake);
        Thread escuta = new Thread(this::escutar, "escuta");
        escuta.setDaemon(true);
        escuta.start();
    }

    private void escutar() {
        while (ativo) {
            try {
                String msg = udp.receber(0);
                if (msg.startsWith("NOTIFY ")) {
                    String[] p = msg.split(" ");
                    tabelaLocal.put(p[1], p[2]);
                    log.info(">>> ATUALIZAÇÃO RECEBIDA E VALIDADA: " + p[1] + " agora é " + p[2]
                            + " (tabela local atualizada)");
                } else {
                    respostas.offer(msg);
                }
            } catch (ErroIntegridadeException e) {
                log.seguranca("Pacote do servidor descartado: " + e.getMessage());
            } catch (IOException e) {
                if (ativo) log.erro("Falha na escuta: " + e.getMessage());
                return;
            }
        }
    }

    public void assinar() throws IOException, InterruptedException {
        udp.enviar("SUBSCRIBE");
        String r = respostas.poll(3, TimeUnit.SECONDS);
        log.info(r == null ? "Sem resposta ao SUBSCRIBE" : "Assinatura confirmada: " + r);
    }

    /** adulteracao == null: consulta normal; senão, envia o pacote deliberadamente adulterado (teste de integridade). */
    public void consultar(String nome, Adulteracao adulteracao) throws IOException, InterruptedException {
        respostas.clear();
        String comando = "QUERY " + nome;
        if (adulteracao != null) udp.enviarAdulterado(comando, adulteracao);
        else udp.enviar(comando);

        String r = respostas.poll(3, TimeUnit.SECONDS);
        if (r == null) {
            log.erro("Sem resposta do DNS em 3 s — o servidor descarta pacotes com HMAC/cifra inválidos sem responder.");
            return;
        }
        if (r.startsWith("ANSWER ")) {
            String[] p = r.split(" ");
            tabelaLocal.put(p[1], p[2]);
            log.info("RESOLUÇÃO VALIDADA: " + p[1] + " -> " + p[2]);
        } else if (r.startsWith("ERRO ")) {
            log.info("O servidor rejeitou a consulta: " + r.substring(5));
        } else {
            log.info("Resposta: " + r);
        }
    }

    /** Teste de autorização: cliente comum (sem credencial de registrador) tenta atualizar um nome. */
    public void tentarUpdate(String nome, String ip) throws IOException, InterruptedException {
        respostas.clear();
        udp.enviar("UPDATE " + nome + " " + ip);
        String r = respostas.poll(3, TimeUnit.SECONDS);
        log.info(r == null ? "Sem resposta ao UPDATE" : "Resposta do DNS ao UPDATE: " + r);
    }

    public void mostrarTabela() { log.info("Tabela local: " + new java.util.TreeMap<>(tabelaLocal)); }

    public void encerrar() { ativo = false; udp.close(); }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Uso: java q1_dns.ClienteDNS <id> [--assinar] [--consultar nome] "
                    + "[--consultar-hmac-corrompido nome] [--consultar-cifrado-adulterado nome] [--handshake-invalido] [--tentar-update nome ip] [--ouvir segundos] [--interativo]");
            return;
        }
        List<String[]> acoes = new ArrayList<>();
        boolean interativo = false;
        boolean handshakeInvalido = false;
        try {
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--assinar" -> acoes.add(new String[]{"assinar"});
                    case "--consultar" -> acoes.add(new String[]{"consultar", args[++i]});
                    case "--consultar-hmac-corrompido" -> acoes.add(new String[]{"corrompido", args[++i]});
                    case "--consultar-cifrado-adulterado" -> acoes.add(new String[]{"adulterado", args[++i]});
                    case "--handshake-invalido" -> handshakeInvalido = true;
                    case "--tentar-update" -> { acoes.add(new String[]{"update", args[i + 1], args[i + 2]}); i += 2; }
                    case "--ouvir" -> acoes.add(new String[]{"ouvir", args[++i]});
                    case "--interativo" -> interativo = true;
                    default -> {
                        System.out.println(Config.MSG_ERRO_ENTRADA + " (opção desconhecida: " + args[i] + ")");
                        return;
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (faltou o valor de uma opção)");
            return;
        }

        ClienteDNS cliente;
        try {
            cliente = new ClienteDNS(args[0]);
            cliente.conectar(handshakeInvalido);
        } catch (IOException | ErroIntegridadeException e) {
            System.out.println(handshakeInvalido
                    ? "Handshake com cifra RSA corrompida: o servidor descartou o KEYX e não respondeu (" + e.getMessage() + ")"
                    : "Servidor DNS indisponível ou handshake falhou: " + e.getMessage());
            return;
        }

        try {
            for (String[] a : acoes) {
                switch (a[0]) {
                    case "assinar" -> cliente.assinar();
                    case "consultar" -> cliente.consultar(a[1], null);
                    case "corrompido" -> cliente.consultar(a[1], Adulteracao.MAC_CORROMPIDO);
                    case "adulterado" -> cliente.consultar(a[1], Adulteracao.CIFRADO_ADULTERADO);
                    case "update" -> cliente.tentarUpdate(a[1], a[2]);
                    case "ouvir" -> {
                        cliente.log.info("Aguardando notificações por " + a[1] + " s...");
                        Thread.sleep(Long.parseLong(a[1]) * 1000);
                    }
                    default -> { }
                }
            }
            if (interativo) cliente.menu();
            cliente.mostrarTabela();
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA);
        } catch (IOException e) {
            System.out.println("Erro de comunicação: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cliente.encerrar();
        }
    }

    private void menu() throws IOException, InterruptedException {
        Scanner teclado = new Scanner(System.in);
        while (true) {
            System.out.println("\n[1] Consultar nome  [2] Assinar notificações  [3] Mostrar tabela local  [0] Sair");
            System.out.print("Opção: ");
            if (!teclado.hasNextLine()) return;
            switch (teclado.nextLine().trim()) {
                case "1" -> {
                    System.out.print("Nome: ");
                    consultar(teclado.hasNextLine() ? teclado.nextLine().trim() : "", null);
                }
                case "2" -> assinar();
                case "3" -> mostrarTabela();
                case "0" -> { return; }
                default -> System.out.println(Config.MSG_ERRO_ENTRADA);
            }
        }
    }
}
