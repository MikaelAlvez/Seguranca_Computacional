package comum;

/**
 * Configurações compartilhadas por todos os componentes.
 * Valores podem ser sobrescritos com -Dpropriedade=valor na linha de comando.
 *
 * ATENÇÃO (didático): tokens/segredos fixos no código são só para simular
 * credenciais pré-distribuídas. Em produção viriam de um cofre de segredos
 * ou seriam substituídos por certificados digitais (PKI).
 */
public final class Config {
    private Config() {}

    public static final String HOST = "127.0.0.1";
    public static final String MSG_ERRO_ENTRADA = "Erro na entrada de dados. Tente outra vez!";

    // ---- Questão 1: Mini-DNS (UDP) ----
    public static final int DNS_PORTA = Integer.getInteger("dns.porta", 5300);
    public static final String TOKEN_REGISTRADOR = "tok-registrador-2026";

    // ---- Questão 2: descoberta de serviços (TCP) ----
    public static final int DIRETORIO_PORTA = Integer.getInteger("dir.porta", 7000);
    public static final String TOKEN_SERVIDOR_CALC = "tok-calc-2026";

    // ---- Questão 3: anel P2P (TCP) ----
    public static final int P2P_PORTA_BASE = Integer.getInteger("p2p.base", 9100);
    public static final int P2P_TAMANHO_ANEL = 6;
    public static final int P2P_ARQUIVOS_POR_NO = 10;

    // ---- Questão 4: CDN ----
    public static final int ORIGEM_PORTA = Integer.getInteger("cdn.origem.porta", 8000);
    // 5353 costuma estar ocupada pelo mDNS (Bonjour/Avahi); por isso usamos 5354.
    public static final int ROUTER_PORTA = Integer.getInteger("cdn.router.porta", 5354);
    public static final int[] EDGE_PORTAS = {8001, 8002, 8003};
    public static final String SEGREDO_ORIGEM = "segredo-origem-cdn-2026";
    public static final int CDN_TTL_SEGUNDOS = Integer.getInteger("cdn.ttl", 10);
    /** Latência simulada da Origem (ms), para tornar MISS visivelmente mais lento que HIT. */
    public static final int ORIGEM_LATENCIA_MS = Integer.getInteger("cdn.latencia", 120);
    public static final int CDN_TOTAL_ARQUIVOS = 1000;
}
