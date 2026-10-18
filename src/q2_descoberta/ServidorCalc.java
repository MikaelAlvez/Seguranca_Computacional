package q2_descoberta;

import comum.Adulteracao;
import comum.CanalSeguro;
import comum.CanalTCP;
import comum.Config;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyPair;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * QUESTÃO 2 — Servidor de CALCULADORA (S1, S2, S3).
 *
 * - Ao iniciar, registra no Diretório (canal cifrado RSA+AES+HMAC) e renova o registro a cada 5 s (heartbeat).
 * - Atende clientes por TCP seguro: CALC|SOMA|a|b  ->  RESULT|valor|nomeDoServidor
 * - Opção --adulterado: simula um servidor comprometido que envia pacotes com HMAC corrompido.
 *
 * Uso: java q2_descoberta.ServidorCalc <nome> <porta> [--adulterado]
 */
public class ServidorCalc {
    private static final String SERVICOS =
            "Calcular soma;Calcular subtração;Calcular multiplicação;Calcular divisão";

    private final String nome;
    private final int porta;
    private final boolean adulterado;
    private final Log log;
    private final KeyPair par = CanalSeguro.gerarParRSA();
    private final AtomicInteger calculos = new AtomicInteger();   // quantos cálculos este nó já processou

    public ServidorCalc(String nome, int porta, boolean adulterado) {
        this.nome = nome;
        this.porta = porta;
        this.adulterado = adulterado;
        this.log = new Log("calc-" + nome);
    }

    public void executar() throws IOException {
        ScheduledExecutorService agenda = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        agenda.scheduleAtFixedRate(this::registrarNoDiretorio, 1, 5, TimeUnit.SECONDS);

        try (ServerSocket servidor = new ServerSocket(porta)) {
            log.info("Servidor de cálculo " + nome + " pronto na porta " + porta
                    + (adulterado ? " [MODO ADULTERADO: envia HMAC corrompido]" : ""));
            while (true) {
                Socket s = servidor.accept();
                new Thread(() -> atender(s), "cliente-" + s.getPort()).start();
            }
        }
    }

    private boolean primeiroRegistro = true;

    private void registrarNoDiretorio() {
        try (CanalTCP canal = CanalTCP.conectar(Config.HOST, Config.DIRETORIO_PORTA,
                "SERVIDOR:" + Config.TOKEN_SERVIDOR_CALC, false, 3000, primeiroRegistro ? log : Log.mudo())) {
            canal.enviar("REGISTER|" + nome + "|" + Config.HOST + "|" + porta + "|" + SERVICOS);
            String resp = canal.receber();
            if (primeiroRegistro) log.info("Registro no diretório: " + resp);
            primeiroRegistro = false;
        } catch (IOException | ErroIntegridadeException e) {
            log.erro("Diretório indisponível (" + e.getMessage() + "); nova tentativa em 5 s");
        }
    }

    private void atender(Socket s) {
        try (CanalTCP canal = CanalTCP.aceitar(s, par, 5000, log, adulterado ? Adulteracao.MAC_CORROMPIDO : null)) {
            String msg;
            while ((msg = canal.receber()) != null) {
                canal.enviar(calcular(msg));
            }
        } catch (ErroIntegridadeException e) {
            log.seguranca("Conexão encerrada: " + e.getMessage());
        } catch (IOException e) {
            log.info("Cliente desconectou (" + e.getMessage() + ")");
        }
    }

    private String calcular(String msg) {
        String[] p = msg.split("\\|", -1);
        if (p.length != 4 || !p[0].equals("CALC")) return erro("comando inválido");
        try {
            BigDecimal a = new BigDecimal(p[2]);
            BigDecimal b = new BigDecimal(p[3]);
            BigDecimal r;
            switch (p[1]) {
                case "SOMA" -> r = a.add(b);
                case "SUBTRACAO" -> r = a.subtract(b);
                case "MULTIPLICACAO" -> r = a.multiply(b);
                case "DIVISAO" -> {
                    if (b.signum() == 0) return "ERRO|Divisão por zero não é permitida";
                    r = a.divide(b, 10, RoundingMode.HALF_UP);
                }
                default -> { return erro("operação desconhecida: " + p[1]); }
            }
            String valor = r.stripTrailingZeros().toPlainString();
            log.info("CÁLCULO: " + p[2] + " " + p[1] + " " + p[3] + " = " + valor + " (processado por " + nome
                    + "; total de cálculos atendidos por " + nome + ": " + calculos.incrementAndGet() + ")");
            return "RESULT|" + valor + "|" + nome;
        } catch (NumberFormatException e) {
            return erro("operandos não numéricos");
        }
    }

    private String erro(String motivo) {
        log.erro(Config.MSG_ERRO_ENTRADA + " (" + motivo + ")");
        return "ERRO|" + Config.MSG_ERRO_ENTRADA;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: java q2_descoberta.ServidorCalc <nome> <porta> [--adulterado]");
            return;
        }
        try {
            boolean adulterado = args.length > 2 && args[2].equals("--adulterado");
            new ServidorCalc(args[0], Integer.parseInt(args[1]), adulterado).executar();
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (porta inválida)");
        } catch (IOException e) {
            System.out.println("Não foi possível iniciar o servidor: " + e.getMessage());
        }
    }
}
