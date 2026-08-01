package cz.kvalitacena.security;

import cz.kvalitacena.db.repo.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generuje výchozí veřejnou identitu ("Modrý čáp #4271") — je to defaultní stav, aby si lidé
 * ze setrvačnosti nedávali skutečné jméno (docs/soukromi.md). Uživatel si ji později může
 * změnit na vlastní přezdívku ({@code display_name}), nikdy ne na reálné jméno.
 */
@Component
@RequiredArgsConstructor
public class HandleGenerator {

  private static final String[] ADJECTIVES = {
      "Modrý", "Zelený", "Tichý", "Rychlý", "Veselý", "Moudrý", "Statečný", "Zvědavý",
      "Ranní", "Pečlivý", "Šetrný", "Ostražitý"
  };

  private static final String[] NOUNS = {
      "čáp", "jezevec", "sokol", "medvěd", "los", "rys", "datel", "jelen", "vlk", "bobr",
      "výr", "jeřáb"
  };

  private final AppUserRepository appUserRepository;
  private final SecureRandom random = new SecureRandom();

  public String generateUnique() {
    for (int attempt = 0; attempt < 50; attempt++) {
      String candidate = ADJECTIVES[random.nextInt(ADJECTIVES.length)] + " "
          + NOUNS[random.nextInt(NOUNS.length)] + " #" + (1000 + random.nextInt(9000));
      if (!appUserRepository.existsByPublicHandle(candidate)) {
        return candidate;
      }
    }
    // Krajně nepravděpodobné (50 kolizí za sebou) — bezpečný pád na UUID úryvek.
    return "Uživatel #" + Long.toString(Math.abs(random.nextLong() % 100_000_000L));
  }
}
