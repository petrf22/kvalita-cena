package cz.kvalitacena.security;

import java.util.UUID;

/**
 * Kdo se ptá — nahrazuje kopírovaný {@code private UUID publicUidOf(Authentication)} z pěti
 * controllerů jedním místem (viz {@link ViewerContextResolver}). {@code userId} je databázové
 * {@code auth.app_user.id}, používá se JEN uvnitř backendu (SQL predikáty, patch tabulky) —
 * ven z API jde vždy {@code publicUid}, nikdy {@code userId} (docs/soukromi.md).
 *
 * <p>Pro {@code @BatchMapping} platí pravidlo z CLAUDE.md: viewer musí být v klíči cache. Spring
 * GraphQL batchuje PER REQUEST, takže viewer z requestu stačí — ale žádná cache PŘES requesty
 * (Caffeine, {@code @Cacheable}) sem nesmí přibýt bez {@code (id, viewerId)} klíče.
 */
public record ViewerContext(UUID publicUid, Long userId, boolean trusted) {

  public static final ViewerContext ANONYMOUS = new ViewerContext(null, null, false);

  public boolean isAnonymous() {
    return userId == null;
  }
}
