# Změny

Verze jsou společné pro server, web i mobil — postup vydání viz [`docs/vydani.md`](docs/vydani.md).
Formát vychází z [Keep a Changelog](https://keepachangelog.com/cs/), text je česky (konvence
repa, viz `CLAUDE.md`). Soubor je zdroj pravdy — `VERSION` a generované seznamy změn na webu
i v mobilu vznikají z něj přes `tools/version/sync.mjs`, needituj je ručně. **Každá položka musí
být na jednom řádku** — parser víceřádkové položky neumí, dlouhý řádek je tu žádoucí kompromis
za jednoduchost skriptu.

## [Nezveřejněno]

## [0.2.1] – 2026-08-28

## [0.2.0] – 2026-08-27

### Přidáno
- Nepovinná platnost akční ceny od–do — po vypršení zmizí z aktuálních cen, historie v grafu zůstává beze změny (server, web, mobil)
- Nepovinná URL obchodu na jeho stránku u řetězce (server, web, mobil)
- Hledání zboží podle čárového kódu, ne jen podle názvu (server, mobil)
- Verze appky viditelná v patičce webu a v „O aplikaci" v mobilu, s odkazem na seznam změn — web na `/changelog`, appka na „Novinky" (server, web, mobil)

### Změněno
- Zápis ceny je v appce schovaný za tlačítkem — appka slouží i lidem, co jen hledají ceny poblíž (mobil)
- Filtry hledání (obchod/město/řazení) přežijí přepnutí záložky (mobil)
- Zvětšená ikona v hlavičce webu, srovnaná tlačítka na stránce přihlášení pod sebe (web)

### Opraveno
- Mapa obchodu jde posunout prstem a umí vybrat obchod ze značek (mobil)
- Pád appky bez FINE oprávnění při zjišťování polohy (mobil)
- Vykreslení mapy na starém místě po skrytí/zobrazení klávesnice (mobil)

## [0.1.0] – 2026-08-24

### Přidáno
- První veřejné vydání — hledání zboží a obchodů, zápis a přehled cen, vývoj v čase a srovnání napříč obchody, OTP přihlášení, samoobslužné smazání účtu (server, web, mobil)
