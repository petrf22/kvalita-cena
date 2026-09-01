package cz.kvalitacena.controller;

import java.util.List;

/**
 * {@code loginRequired=true} znamená prázdné {@code items} bez ohledu na {@code totalCount}
 * (docs/reputace.md, T1: texty recenzí vidí jen přihlášený) — klient tak umí zobrazit "N
 * recenzí, přihlas se pro zobrazení" místo prázdného stavu.
 */
public record ProductReviewResult(List<ReviewItem> items, int totalCount, boolean hasMore,
    boolean loginRequired) {
}
