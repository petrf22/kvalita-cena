# Přehled dokumentace

Rozcestník po dokumentech v `docs/` — účel, publikum, charakter a co je pro daný dokument
zdroj pravdy. Charakter je jedna ze čtyř hodnot:

- **živý stav** — mění se s kódem, může zastarat, pokud se zapomene aktualizovat spolu se
  změnou.
- **rozhodnutí** — proč je architektura taková, jaká je; mění se jen s revizí rozhodnutí,
  ne s každým commitem.
- **plán** — cílový stav, který zatím není napsaný jako kód.
- **runbook** — postup pro člověka, který ho provádí ručně.

Dokument může nést víc charakterů zároveň (typicky rozhodnutí + živý stav vedle sebe pod
sufixem jako „MVP“) — to je u dokumentů níž vyznačené.

| Dokument | Charakter | Zdroj pravdy pro |
|---|---|---|
| [`stav-implementace.md`](stav-implementace.md) | živý stav | co je hotové, v jakém souboru to žije |
| [`datovy-model.md`](datovy-model.md) | rozhodnutí | proč jsou tabulky rozdělené tak, jak jsou (ne výpis sloupců — ten je v Liquibase) |
| [`reputace.md`](reputace.md) | rozhodnutí + cílový stav | **všechny vzorce a prahy** reputace/vah — nikde jinde v kódu nemají žít jako konstanty |
| [`soukromi.md`](soukromi.md) | rozhodnutí | pravidla privacy-by-design a jejich vědomé výjimky |
| [`lokalizace.md`](lokalizace.md) | rozhodnutí + živý stav | jazyky, mapa země→měna→locale, kontrakt chyb |
| [`ai.md`](ai.md) | rozhodnutí + plán | lokální AI — žádný kód zatím neexistuje |
| [`branding.md`](branding.md) | reference | vizuální identita, `tools/icons/generate.py` |
| [`rozvoj.md`](rozvoj.md) | plán / backlog | nápady mimo aktuální stadium, se stavem NÁPAD/ROZHODNOUT/PLÁNOVÁNO/ČÁSTEČNĚ |
| [`vydani.md`](vydani.md) | runbook + historie | postup mobilního vydání, podpisový klíč, Play Console |
| [`nasazeni.md`](nasazeni.md) | runbook (checklist) | produkční hosting backendu a webu |
| [`spusteni.md`](spusteni.md) | runbook (vývojářský) | lokální rozjezd pro vývoj |
| [`podminky-uziti.md`](podminky-uziti.md) | právní text | podmínky užití — platné znění, historie v gitu |
| [`zasady-ochrany-osobnich-udaju.md`](zasady-ochrany-osobnich-udaju.md) | právní text | zásady ochrany osobních údajů — platné znění, historie v gitu |

Mimo `docs/`, ale patřičné sem: [`ops/README.md`](../ops/README.md) je produkční provozní
runbook (deploy, zálohy, obnova) — žije mimo `docs/`, protože ho čte hlavně provozovatel na
serveru, ne vývojář v repu.

## Terminologie fází

Napříč docs se dřív používaly **tři různé osy** pod slovy „etapa“/„fáze“, dvě z nich sdílely
stejná čísla pro úplně jiné věci (etapa 2 = recenze/plný vzorec `S` na jednom místě, etapa 2 =
přidání němčiny na jiném). Od téhle revize platí jedna osa zralosti produktu:

**MVP → uzavřená beta → veřejná beta → další rozvoj**

- **MVP** — dnešní implementovaný rozsah (`docs/stav-implementace.md`), prochozí kostra
  end-to-end.
- **Uzavřená beta** — osobně pozvaní testeři, appka neindexovaná (`docs/nasazeni.md`, „Než
  pozvat první lidi“).
- **Veřejná beta** — veřejné spuštění, appka indexovaná, dedikovaný SMTP, obrana formuláře
  zpětné vazby proti spamu (`docs/nasazeni.md`, položky „Před veřejnou betou“).
- **Další rozvoj** — vše za veřejnou betou: textové recenze, skupiny důvěry, plný reputační
  vzorec `S`, lokální dodavatelé a nápady v `docs/rozvoj.md`. Nedělí se dál na podfáze podle
  data — priorita je vyjádřená stavem položky (NÁPAD/ROZHODNOUT/PLÁNOVÁNO/ČÁSTEČNĚ), ne
  číslem fáze.

Samostatná, nezávislá osa je **vlna expanze 1/2/3+** (`docs/lokalizace.md`) — rozšiřování
appky o další ZEMĚ a JAZYKY, nemá se svým číslováním nic společného s osou zralosti výš.

Verze **0.x** (`VERSION`/`CHANGELOG.md`) je třetí, taky nezávislá osa — SemVer, negeneruje se
z fáze ani z vlny expanze.

## Zdroj rozhodnutí, které v repu nejsou

Rozhodnutí a jejich odůvodnění se dřív odkazovala na samostatný plánovací soubor mimo repo
(„paměť“) — ten přestal existovat. Od teď je **jediný zdroj rozhodnutí tenhle `docs/`
adresář** — pokud rozhodnutí ještě nemá kam patřit, patří do `docs/rozvoj.md` jako položka se
stavem ROZHODNOUT, ne do poznámky mimo repo.
