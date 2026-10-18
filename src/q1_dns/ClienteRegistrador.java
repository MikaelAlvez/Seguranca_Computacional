package q1_dns;

import comum.Config;
import comum.ClienteUDPSeguro;
import comum.ErroIntegridadeException;
import comum.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * QUESTÃO 1 — Cliente REGISTRADOR: atualiza um nome (binding dinâmico).
 * Apresenta, no handshake (cifrado com RSA), a credencial "REGISTRADOR:token".
 *
 * Uso: java q1_dns.ClienteRegistrador [--token-errado] [<nome> <ip>]     (sem nome/ip: modo interativo)
 *   --token-errado  usa uma credencial inválida (teste do caminho "não autorizado").
 *
 * Quando há erro:
 *   - nº de argumentos errado ................. o próprio cliente imprime o uso (nenhum pacote é enviado);
 *   - linha inválida no modo interativo ....... o cliente imprime "Erro na entrada de dados. Tente outra vez!";
 *   - nome ou IP inválido ..................... o DNS recusa (ERRO ...) e o mapa NÃO muda;
 *   - credencial ausente/errada ............... o DNS responde ERRO NAO_AUTORIZADO e registra UPDATE NEGADO;
 *   - DNS fora do ar .......................... o handshake esgota as 3 tentativas e o cliente avisa;
 *   - pacote sem HMAC válido .................. o DNS descarta sem responder (o cliente avisa que não houve resposta).
 */
public class ClienteRegistrador {
    public static void main(String[] argumentos) {
        List<String> args = new ArrayList<>(List.of(argumentos));
        boolean tokenErrado = args.remove("--token-errado");
        if (args.size() != 0 && args.size() != 2) {
            System.out.println("Uso: java q1_dns.ClienteRegistrador [--token-errado] [<nome> <ip>]");
            return;
        }
        String credencial = "REGISTRADOR:" + (tokenErrado ? "token-invalido" : Config.TOKEN_REGISTRADOR);

        Log log = new Log("dns-registrador");
        try (ClienteUDPSeguro udp = new ClienteUDPSeguro(new InetSocketAddress(Config.HOST, Config.DNS_PORTA), 0, log)) {
            udp.handshake(credencial, false);
            if (args.size() == 2) {
                atualizar(udp, log, args.get(0), args.get(1));
            } else {
                Scanner teclado = new Scanner(System.in);
                System.out.println("Digite '<nome> <ip>' (ou 'sair'):");
                while (teclado.hasNextLine()) {
                    String linha = teclado.nextLine().trim();
                    if (linha.equalsIgnoreCase("sair")) break;
                    String[] p = linha.split("\\s+");
                    if (p.length != 2) { System.out.println(Config.MSG_ERRO_ENTRADA); continue; }
                    atualizar(udp, log, p[0], p[1]);
                }
            }
        } catch (SocketTimeoutException e) {
            System.out.println("Servidor DNS sem resposta: " + e.getMessage());
        } catch (IOException | ErroIntegridadeException e) {
            System.out.println("Falha na comunicação com o DNS: " + e.getMessage());
        }
    }

    private static void atualizar(ClienteUDPSeguro udp, Log log, String nome, String ip) throws IOException {
        udp.enviar("UPDATE " + nome + " " + ip);
        try {
            String r = udp.receber(3000);
            if (r.startsWith("OK UPDATED")) {
                log.info("ATUALIZAÇÃO CONFIRMADA pelo DNS: " + nome + " -> " + ip);
            } else if (r.equals("ERRO NAO_AUTORIZADO")) {
                log.erro("ATUALIZAÇÃO NEGADA: este registrador não tem credencial válida no DNS");
            } else if (r.startsWith("ERRO ")) {
                log.erro("ATUALIZAÇÃO REJEITADA pelo DNS: " + r.substring(5));
            } else {
                log.info("Resposta do DNS: " + r);
            }
        } catch (SocketTimeoutException e) {
            log.erro("Sem resposta do DNS em 3 s (pacote descartado? HMAC inválido?)");
        } catch (ErroIntegridadeException e) {
            log.seguranca("Resposta do DNS com integridade inválida: " + e.getMessage());
        }
    }
}
