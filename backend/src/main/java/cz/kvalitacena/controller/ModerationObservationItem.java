package cz.kvalitacena.controller;

import cz.kvalitacena.db.entity.PriceObservation;

import java.util.UUID;

/**
 * Cena k moderátorskému přezkumu (docs/reputace.md, "Moderace") — nesouhlas s cenou nejde
 * nahlásit komunitně (core.record_flag míří jen na katalogové záznamy), takže sem moderátor
 * přistupuje přímo přes autora/zboží/obchod, ne přes frontu.
 */
public record ModerationObservationItem(PriceObservation observation, UUID authorPublicUid, String authorHandle,
                                          PublicationStatus productPublication) {
}
