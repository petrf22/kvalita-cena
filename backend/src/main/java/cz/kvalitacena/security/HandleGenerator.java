package cz.kvalitacena.security;

import cz.kvalitacena.db.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generuje výchozí veřejnou identitu ("Modrý čáp #4271") — je to defaultní stav, aby si lidé
 * ze setrvačnosti nedávali skutečné jméno (docs/soukromi.md). Uživatel si ji později může
 * změnit na vlastní přezdívku ({@code display_name}), nikdy ne na reálné jméno.
 *
 * <p>Skládání věty je jazykově závislé — v cs/sk/pl se přídavné jméno ohýbá podle rodu
 * podstatného ("Modrý čáp" vs. "Modrá liška"), takže se tu ukládá jen STRUKTURA (anglické
 * klíče + rod substantiva) a vyrenderuje se až podle jazyka čtenáře v {@code
 * ViewerGraphQlController} přes {@code Messages} a bundly {@code handles*.properties}
 * (docs/lokalizace.md). {@link #canonicalHandle} je jazykově neutrální klíč, na kterém visí
 * {@code unique} constraint — unikátnost se kontroluje nad NÍM, ne nad vyrenderovaným textem,
 * jinak by se dva účty mohly srazit v jednom jazyce a v jiném ne.
 */
@Component
@RequiredArgsConstructor
public class HandleGenerator {

  public enum Gender { M, F, N }

  public record HandleNoun(String key, Gender gender) {
  }

  public record GeneratedHandle(String adjective, HandleNoun noun, int number) {
    public String canonicalHandle() {
      return adjective + "-" + noun.key() + "-" + number;
    }
  }

  private static final String[] ADJECTIVES = {
      "blue", "green", "quiet", "quick", "cheerful", "wise", "brave", "curious",
      "morning", "careful", "thrifty", "watchful"
  };

  private static final HandleNoun[] NOUNS = {
      new HandleNoun("stork", Gender.M), new HandleNoun("badger", Gender.M),
      new HandleNoun("falcon", Gender.M), new HandleNoun("bear", Gender.M),
      new HandleNoun("elk", Gender.M), new HandleNoun("lynx", Gender.M),
      new HandleNoun("woodpecker", Gender.M), new HandleNoun("deer", Gender.M),
      new HandleNoun("wolf", Gender.M), new HandleNoun("beaver", Gender.M),
      new HandleNoun("owl", Gender.M), new HandleNoun("crane", Gender.M),
  };

  /**
   * Rod podstatného podle uloženého klíče ({@code auth.app_user.handle_noun}) — potřebuje ho
   * {@code ViewerGraphQlController} při renderování, protože ukládá jen klíč, ne celý {@link
   * HandleNoun}. Neznámý klíč (teoreticky stará/poškozená data) padá na Gender.M.
   */
  public static final Map<String, Gender> NOUN_GENDERS = Arrays.stream(NOUNS)
      .collect(Collectors.toMap(HandleNoun::key, HandleNoun::gender));

  private final AppUserRepository appUserRepository;
  private final SecureRandom random = new SecureRandom();

  public GeneratedHandle generateUnique() {
    for (int attempt = 0; attempt < 50; attempt++) {
      GeneratedHandle candidate = new GeneratedHandle(
          ADJECTIVES[random.nextInt(ADJECTIVES.length)],
          NOUNS[random.nextInt(NOUNS.length)],
          1000 + random.nextInt(9000));
      if (!appUserRepository.existsByPublicHandle(candidate.canonicalHandle())) {
        return candidate;
      }
    }
    // Krajně nepravděpodobné (50 kolizí za sebou) — bezpečný pád na širší rozsah čísla
    // (pořád v mezích SMALLINT sloupce handle_number, viz 2026-08-09-04-….yaml).
    HandleNoun fallbackNoun = NOUNS[random.nextInt(NOUNS.length)];
    return new GeneratedHandle("blue", fallbackNoun, 1000 + random.nextInt(30_000));
  }
}
