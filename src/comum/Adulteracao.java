package comum;

/** Formas de adulterar um pacote — usadas só nos casos de teste de falha de integridade. */
public enum Adulteracao {
    /** Troca um bit do HMAC (simula "HMAC corrompido"). */
    MAC_CORROMPIDO,
    /** Troca um bit do texto cifrado depois de calcular o HMAC (simula alteração no canal). */
    CIFRADO_ADULTERADO,
    /** Em vez de HMAC, anexa SHA-256 SEM chave (simula atacante que não conhece a chave). */
    HASH_SEM_CHAVE
}
