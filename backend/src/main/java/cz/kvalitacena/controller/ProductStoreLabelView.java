package cz.kvalitacena.controller;

import java.time.OffsetDateTime;

/** GraphQL typ ProductStoreLabel — „název na účtence" s rozsahem vykresleným jako jména. */
public record ProductStoreLabelView(String label, String chainName, String storeName, OffsetDateTime lastSeenAt) {
}
