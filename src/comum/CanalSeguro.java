package comum;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Criptografia HÍBRIDA usada por TODAS as mensagens do projeto.
 *
 * 1) HANDSHAKE (assimétrico, RSA-2048 + OAEP-SHA256):
 *      cliente gera chave AES-128 + chave HMAC-256 aleatórias (SecureRandom) e as envia
 *      cifradas com a chave PÚBLICA do servidor  ->  só o servidor (chave privada) as lê.
 *
 * 2) MENSAGENS (simétrico, AES-128-CBC + HMAC-SHA256, "Encrypt-then-MAC"):
 *      pacote = sid.direcao.seq.IV.cifrado.HMAC   (campos em Base64, separados por '.')
 *      HMAC cobre tudo antes dele: sid, direção, sequência, IV e texto cifrado.
 *
 * O receptor SEMPRE verifica o HMAC (em tempo constante) ANTES de decifrar.
 */
public final class CanalSeguro {
    private CanalSeguro() {}

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final OAEPParameterSpec OAEP = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final int TAM_AES = 16;   // AES-128
    private static final int TAM_MAC = 32;   // HMAC-SHA256

    // ------------------------------------------------------------------ chaves RSA

    public static KeyPair gerarParRSA() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, RNG);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA indisponível na JVM", e);
        }
    }

    public static String publicaParaBase64(PublicKey pub) { return B64.encodeToString(pub.getEncoded()); }

    public static PublicKey publicaDeBase64(String b64) throws ErroIntegridadeException {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(B64D.decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ErroIntegridadeException("chave pública recebida é inválida", e);
        }
    }

    // ------------------------------------------------------------------ handshake

    /** Lado CLIENTE: sorteia as chaves simétricas da sessão. */
    public static SessaoSegura novaSessaoCliente(String credencial) {
        byte[] aes = new byte[TAM_AES];
        byte[] mac = new byte[TAM_MAC];
        byte[] id = new byte[8];
        RNG.nextBytes(aes);
        RNG.nextBytes(mac);
        RNG.nextBytes(id);
        return new SessaoSegura(HexFormat.of().formatHex(id), aes, mac,
                credencial == null ? "" : credencial, true);
    }

    /** Lado CLIENTE: monta "KEYX:sid:RSA(aes|mac|credencial)". Se corromper=true, estraga o RSA (teste). */
    public static String montarKEYX(SessaoSegura s, PublicKey pubServidor, boolean corromper) {
        try {
            byte[] cred = s.credencial().getBytes(StandardCharsets.UTF_8);
            if (cred.length > 100) throw new IllegalArgumentException("credencial muito longa");
            byte[] claro = new byte[TAM_AES + TAM_MAC + cred.length];
            System.arraycopy(s.chaveAes(), 0, claro, 0, TAM_AES);
            System.arraycopy(s.chaveMac(), 0, claro, TAM_AES, TAM_MAC);
            System.arraycopy(cred, 0, claro, TAM_AES + TAM_MAC, cred.length);

            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.ENCRYPT_MODE, pubServidor, OAEP);
            byte[] cifrado = rsa.doFinal(claro);
            if (corromper) cifrado[cifrado.length / 2] ^= 0x5A;   // simula erro no handshake
            return "KEYX:" + s.sid() + ":" + B64.encodeToString(cifrado);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao cifrar handshake com RSA", e);
        }
    }

    /** Lado SERVIDOR: decifra o KEYX com a chave PRIVADA e reconstrói a sessão. */
    public static SessaoSegura processarKEYX(String keyx, PrivateKey privada) throws ErroIntegridadeException {
        String[] p = keyx.trim().split(":", 3);
        if (p.length != 3 || !p[0].equals("KEYX") || !p[1].matches("[0-9a-f]{16}")) {
            throw new ErroIntegridadeException("mensagem de handshake malformada");
        }
        try {
            Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE, privada, OAEP);
            byte[] claro = rsa.doFinal(B64D.decode(p[2]));
            if (claro.length < TAM_AES + TAM_MAC) throw new ErroIntegridadeException("handshake curto demais");
            byte[] aes = Arrays.copyOfRange(claro, 0, TAM_AES);
            byte[] mac = Arrays.copyOfRange(claro, TAM_AES, TAM_AES + TAM_MAC);
            String cred = new String(claro, TAM_AES + TAM_MAC, claro.length - TAM_AES - TAM_MAC,
                    StandardCharsets.UTF_8);
            return new SessaoSegura(p[1], aes, mac, cred, false);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ErroIntegridadeException("erro de decifragem RSA no handshake", e);
        }
    }

    // ------------------------------------------------------------------ mensagens

    public static String proteger(SessaoSegura s, String texto) {
        return proteger(s, texto, null);
    }

    /** Cifra (AES-CBC) e autentica (HMAC). Se 'adulteracao' != null, estraga o pacote de propósito (testes). */
    public static String proteger(SessaoSegura s, String texto, Adulteracao adulteracao) {
        try {
            long seq = s.proximoSeq();
            String dir = s.souCliente() ? "C" : "S";
            byte[] iv = new byte[16];
            RNG.nextBytes(iv);

            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(s.chaveAes(), "AES"), new IvParameterSpec(iv));
            byte[] ct = aes.doFinal(texto.getBytes(StandardCharsets.UTF_8));

            String ivB = B64.encodeToString(iv);
            String ctB = B64.encodeToString(ct);
            byte[] mac = hmac(s.chaveMac(), (s.sid() + "." + dir + "." + seq + "." + ivB + "." + ctB)
                    .getBytes(StandardCharsets.UTF_8));

            if (adulteracao == Adulteracao.CIFRADO_ADULTERADO) {
                byte[] ctRuim = ct.clone();
                ctRuim[0] ^= 0x01;
                ctB = B64.encodeToString(ctRuim);
            } else if (adulteracao == Adulteracao.MAC_CORROMPIDO) {
                mac[0] ^= 0x01;
            } else if (adulteracao == Adulteracao.HASH_SEM_CHAVE) {
                mac = MessageDigest.getInstance("SHA-256").digest(
                        (s.sid() + "." + dir + "." + seq + "." + ivB + "." + ctB).getBytes(StandardCharsets.UTF_8));
            }
            return s.sid() + "." + dir + "." + seq + "." + ivB + "." + ctB + "." + B64.encodeToString(mac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("falha ao proteger mensagem", e);
        }
    }

    /** Verifica HMAC, direção e sequência; só então decifra. */
    public static String abrir(SessaoSegura s, String pacote) throws ErroIntegridadeException {
        String[] p = pacote.trim().split("\\.", -1);
        if (p.length != 6) throw new ErroIntegridadeException("formato de pacote inválido");
        if (!p[0].equals(s.sid())) throw new ErroIntegridadeException("identificador de sessão divergente");
        String dirEsperada = s.souCliente() ? "S" : "C";
        if (!p[1].equals(dirEsperada)) {
            throw new ErroIntegridadeException("direção inválida (possível reflexão de pacote)");
        }
        try {
            byte[] macRecebido = B64D.decode(p[5]);
            byte[] macCalculado = hmac(s.chaveMac(),
                    (p[0] + "." + p[1] + "." + p[2] + "." + p[3] + "." + p[4]).getBytes(StandardCharsets.UTF_8));
            // comparação em TEMPO CONSTANTE (evita timing attack)
            if (!MessageDigest.isEqual(macCalculado, macRecebido)) {
                throw new ErroIntegridadeException("HMAC inválido: mensagem adulterada ou sem a chave correta");
            }
            long seq = Long.parseLong(p[2]);
            if (!s.registrarSeqRecebido(seq)) {
                throw new ErroIntegridadeException("número de sequência repetido (possível replay)");
            }
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(s.chaveAes(), "AES"),
                    new IvParameterSpec(B64D.decode(p[3])));
            return new String(aes.doFinal(B64D.decode(p[4])), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ErroIntegridadeException("pacote com codificação inválida", e);
        } catch (GeneralSecurityException e) {
            throw new ErroIntegridadeException("falha ao decifrar o pacote", e);
        }
    }

    private static byte[] hmac(byte[] chave, byte[] dados) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(chave, "HmacSHA256"));
        return mac.doFinal(dados);
    }
}
