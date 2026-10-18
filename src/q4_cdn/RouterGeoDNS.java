package q4_cdn;

import comum.Config;
import comum.ServidorUDPSeguro;
import comum.SessaoSegura;
import comum.Validacao;

import java.net.SocketException;
import java.util.Map;

/**
 * QUESTÃO 4 — ROUTER / GeoDNS (UDP).
 * Recebe (cifrado) "ONDE|arquivoN.ext|<localização fictícia>" e responde "EDGE|ip|porta|nome"
 * com o Edge mais próximo da localização informada.
 */
public final class RouterGeoDNS extends ServidorUDPSeguro {
    private static final Map<String, Integer> EDGE_POR_REGIAO = Map.of(
            "BR-NE", 8001,   // Nordeste do Brasil
            "BR-SE", 8002,   // Sudeste do Brasil
            "US", 8003);     // Estados Unidos
    private static final int EDGE_PADRAO = 8001;

    public RouterGeoDNS(int porta) throws SocketException {
        super("cdn-router", porta);
    }

    @Override
    protected void tratarMensagem(SessaoSegura sessao, String mensagem) {
        String[] p = mensagem.split("\\|", -1);
        if (p.length != 3 || !p[0].equals("ONDE")
                || Validacao.numeroArquivo(p[1], Config.CDN_TOTAL_ARQUIVOS) < 0
                || !p[2].trim().matches("[A-Za-z]{2}(-[A-Za-z]{1,3})?")) {      // ex.: BR-NE, US, br-se
            log.erro(Config.MSG_ERRO_ENTRADA + " (consulta de roteamento inválida: arquivo ou localização fora do padrão)");
            responder(sessao, "ERRO|" + Config.MSG_ERRO_ENTRADA);
            return;
        }
        String regiao = p[2].trim().toUpperCase();
        Integer porta = EDGE_POR_REGIAO.get(regiao);
        if (porta == null) {
            porta = EDGE_PADRAO;
            log.info("Localização desconhecida '" + p[2] + "' -> usando Edge padrão");
        }
        log.info("GeoDNS: cliente em " + regiao + " pediu " + p[1] + " -> Edge mais próximo: " + Config.HOST + ":" + porta);
        responder(sessao, "EDGE|" + Config.HOST + "|" + porta + "|edge-" + porta);
    }

    public static void main(String[] args) {
        try {
            new RouterGeoDNS(args.length > 0 ? Integer.parseInt(args[0]) : Config.ROUTER_PORTA).executar();
        } catch (NumberFormatException e) {
            System.out.println(Config.MSG_ERRO_ENTRADA + " (porta inválida)");
        } catch (Exception e) {
            System.out.println("Não foi possível iniciar o Router: " + e.getMessage());
        }
    }
}
