// Generováno tools/version/sync.mjs z kořenového CHANGELOG.md — needituj ručně.
// Zobrazuje features/changelog/changelog-page.ts, text je záměrně jen česky (CLAUDE.md,
// docs/lokalizace.md — legal.czechOnlyNotice vzor), UI okolo je přeložené.

export interface ChangelogItem {
  text: string;
  parts: string[];
}

export interface ChangelogSection {
  title: string;
  items: ChangelogItem[];
}

export interface ChangelogRelease {
  version: string;
  date: string;
  sections: ChangelogSection[];
}

// Nejnovější vydání první.
export const CHANGELOG: ChangelogRelease[] = [
  { version: "0.2.0", date: "2026-08-27", sections: [
      { title: "Přidáno", items: [
        { text: "Nepovinná platnost akční ceny od–do — po vypršení zmizí z aktuálních cen, historie v grafu zůstává beze změny", parts: ["server", "web", "mobil"] },
        { text: "Nepovinná URL obchodu na jeho stránku u řetězce", parts: ["server", "web", "mobil"] },
        { text: "Hledání zboží podle čárového kódu, ne jen podle názvu", parts: ["server", "mobil"] },
        { text: "Verze appky viditelná v patičce webu a v „O aplikaci\" v mobilu, s odkazem na seznam změn — web na `/changelog`, appka na „Novinky\"", parts: ["server", "web", "mobil"] },
      ] },
      { title: "Změněno", items: [
        { text: "Zápis ceny je v appce schovaný za tlačítkem — appka slouží i lidem, co jen hledají ceny poblíž", parts: ["mobil"] },
        { text: "Filtry hledání (obchod/město/řazení) přežijí přepnutí záložky", parts: ["mobil"] },
        { text: "Zvětšená ikona v hlavičce webu, srovnaná tlačítka na stránce přihlášení pod sebe", parts: ["web"] },
      ] },
      { title: "Opraveno", items: [
        { text: "Mapa obchodu jde posunout prstem a umí vybrat obchod ze značek", parts: ["mobil"] },
        { text: "Pád appky bez FINE oprávnění při zjišťování polohy", parts: ["mobil"] },
        { text: "Vykreslení mapy na starém místě po skrytí/zobrazení klávesnice", parts: ["mobil"] },
      ] },
  ] },
  { version: "0.1.0", date: "2026-08-24", sections: [
      { title: "Přidáno", items: [
        { text: "První veřejné vydání — hledání zboží a obchodů, zápis a přehled cen, vývoj v čase a srovnání napříč obchody, OTP přihlášení, samoobslužné smazání účtu", parts: ["server", "web", "mobil"] },
      ] },
  ] },
];
