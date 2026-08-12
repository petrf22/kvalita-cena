package cz.kvalitacena.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

/**
 * Jediné místo, které umí pracovat s e-mailem uživatele i s ostatní textovou PII profilu —
 * viz docs/soukromi.md.
 *
 * <p>{@code emailHash} je deterministický HMAC-SHA256 s pepperem mimo databázi — používá se
 * jen k VYHLEDÁNÍ účtu, e-mail se z něj nedá získat zpět. {@code emailEnc} je AES-256-GCM,
 * klíč taky mimo databázi — nutné, protože appka musí umět e-mail skutečně přečíst (odeslat
 * OTP, notifikaci). Únik databázového dumpu bez těchto dvou hodnot z env neodhalí e-maily.
 *
 * <p>{@link #encryptValue}/{@link #decryptValue} používají stejný AES klíč pro jméno/příjmení/
 * telefon/kontaktní e-mail profilu ({@code auth.user_profile}, {@code UserProfileService}) —
 * na rozdíl od {@link #encrypt}/{@link #decrypt} BEZ normalizace na malá písmena, protože
 * "Jan Novák" a "jan novák" nejsou zaměnitelné jako e-mailová adresa.
 */
@Component
public class EmailCipher {

  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;

  @Value("${app.security.email-hash-pepper}")
  private String pepperBase64;

  @Value("${app.security.email-enc-key}")
  private String keyBase64;

  private SecretKeySpec hmacKey;
  private SecretKeySpec aesKey;
  private final SecureRandom secureRandom = new SecureRandom();

  @PostConstruct
  void init() {
    hmacKey = new SecretKeySpec(Base64.getDecoder().decode(pepperBase64), "HmacSHA256");
    aesKey = new SecretKeySpec(Base64.getDecoder().decode(keyBase64), "AES");
  }

  /** lowercase + trim — bez normalizace plus-adresování (dost pro MVP). */
  public String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  public byte[] hash(String email) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(hmacKey);
      return mac.doFinal(normalize(email).getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("Nepodařilo se spočítat hash e-mailu", e);
    }
  }

  public byte[] encrypt(String email) {
    return encryptValue(normalize(email));
  }

  public String decrypt(byte[] encrypted) {
    return decryptValue(encrypted);
  }

  /** Šifrování obecné textové PII (jméno, příjmení, telefon, kontaktní e-mail) — BEZ normalizace. */
  public byte[] encryptValue(String value) {
    try {
      byte[] iv = new byte[GCM_IV_LENGTH];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] result = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, result, 0, iv.length);
      System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Nepodařilo se zašifrovat hodnotu", e);
    }
  }

  public String decryptValue(byte[] encrypted) {
    try {
      byte[] iv = Arrays.copyOfRange(encrypted, 0, GCM_IV_LENGTH);
      byte[] ciphertext = Arrays.copyOfRange(encrypted, GCM_IV_LENGTH, encrypted.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
      return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Nepodařilo se dešifrovat hodnotu", e);
    }
  }

  /** @return doména e-mailu malými písmeny, nebo null, pokud e-mail nemá tvar x@y. */
  public String extractDomain(String email) {
    String normalized = normalize(email);
    int at = normalized.lastIndexOf('@');
    return at < 0 ? null : normalized.substring(at + 1);
  }
}
