package cz.kvalitacena.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Zpětná vazba od uživatele appky (core.feedback, docs/nasazeni.md „Než pozvat první lidi").
 * Prahy patří sem, ne natvrdo do kódu (stejná konvence jako {@link ModerationProperties}).
 */
@Component
@ConfigurationProperties(prefix = "app.feedback")
@Data
public class FeedbackProperties {
  /** Strop délky zprávy — appka nemá jinou obranu proti nesmyslně dlouhému vstupu. */
  private int maxMessageLength;
  /** Strop délky volitelně přiloženého stacktrace posledního pádu (mobil). */
  private int maxDiagnosticsLength;
  /** Denní strop na IP — první vrstva u ANONYMNÍHO odeslání (žádný userId k dispozici). */
  private int maxPerDayPerIp;
  /** Denní strop na podsíť (IPv4 /24, IPv6 /48) — proti rotaci IP v jednom rozsahu. */
  private int maxPerDayPerSubnet;
  /** Denní strop na přihlášeného uživatele. */
  private int maxPerDayPerUser;
  /** Hodinový strop přes VŠECHNA anonymní odeslání dohromady — tvrdá brzda proti náporu
   *  z mnoha různých IP. Přihlášené odesílatele neomezuje, ti mají vlastní strop výš. */
  private int maxAnonymousPerHour;
  /** Denní obdoba {@link #maxAnonymousPerHour}. */
  private int maxAnonymousPerDay;

  private Challenge challenge = new Challenge();
  private Spam spam = new Spam();

  /** Proof-of-work výzva (docs/nasazeni.md, obrana proti spamu) — {@link
   *  cz.kvalitacena.security.FeedbackChallengeService}. */
  @Data
  public static class Challenge {
    /** V beta profilu false (starší APK výzvu ještě neumí) — viz application-beta.yml. */
    private boolean required = true;
    /** Kolik vedoucích nulových bitů musí mít SHA-256(salt + ":" + nonce). */
    private int difficulty = 18;
    private Duration ttl = Duration.ofMinutes(5);
    /** Odpověď rychlejší než tohle je podezřelá — bot řeší výzvu, sotva ji dostane. */
    private Duration minAge = Duration.ofMillis(300);
  }

  /** Prahy {@link cz.kvalitacena.service.FeedbackSpamDetector}. */
  @Data
  public static class Spam {
    /** Součet skóre od tohoto čísla výš jde zpráva do karantény, ne do běžné fronty. */
    private int quarantineThreshold = 50;
    /** Víc odkazů ve zprávě než tohle přidává skóre (typická komerční spam zpráva). */
    private int maxLinks = 2;
  }
}
