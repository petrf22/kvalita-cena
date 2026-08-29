package cz.kvalitacena.db.entity;

/**
 * Druh fotky (core.media.photo_kind) — nezávislá osa od {@link RecordType} (ten říká čí je
 * fotka, tohle co na ní je). Přidáno rovnou se sloty na fotky ve formuláři nového zboží
 * (docs/rozvoj.md), ne až dodatečně — docs/ai.md u {@code f_evid} varuje přesně před opačným
 * postupem: druh musí schéma nést od začátku. {@code ITEM}, ne {@code PRODUCT} — {@link
 * RecordType#PRODUCT} už znamená totéž na jiné ose, dvojice by byla matoucí.
 *
 * <p>{@code LABEL} je zamýšlený budoucí vstup pro čtení složení/textu z etikety (docs/ai.md).
 * Fotky provozoven ({@link RecordType#STORE}) i avatar ({@link RecordType#USER}) druh
 * nerozlišují, zůstávají na výchozí hodnotě {@link #OTHER}.
 */
public enum PhotoKind {
  ITEM,
  LABEL,
  OTHER
}
