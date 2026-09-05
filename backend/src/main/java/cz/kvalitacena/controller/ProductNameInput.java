package cz.kvalitacena.controller;

/**
 * Název zboží v jednom jazyce. Ve vstupech jde vždy o DALŠÍ jazyky vedle povinné dvojice
 * {@code name}/{@code nameLang} — primární název má vlastní pole, protože bez něj nemá zboží
 * jak vzniknout.
 */
public record ProductNameInput(String lang, String name) {
}
