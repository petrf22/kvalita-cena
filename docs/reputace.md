# Reputace a váhy

Jedno zadání určuje celý tento systém: komunita má být pozitivní, ne toxická. To vylučuje
veřejné negativní hodnocení uživatelů, i když by bylo technicky nejjednodušší — viz níže.

> **Stav v MVP:** implementovaná je zatím jen složka `L` (anonym 0,15 / registrovaný
> 1,00) a vážený medián v agregaci. Zbytek vzorce (přesnost, zkušenost, stáří účtu, penalizace,
> detekce zneužití, skupiny důvěry) je popsaný tady jako cílový stav pro další rozvoj, aby ladění
> mělo od začátku jedno místo, kam patří — ne aby se prahy rozsely po kódu.

## Reputační skóre — čítače s exponenciálním útlumem

Skóre se nepočítá z historie jednotlivých událostí, ale jako průběžně aktualizovaný čítač:

```
X ← X · 0.5^((t_now − t_prev) / H) + Δ
```

kde `H` je poločas rozpadu. Výpočet je O(1) a nepotřebuje log událostí — což je to, co
umožňuje po 180 dnech (nebo při výmazu účtu, `docs/soukromi.md`, „GDPR") smazat vazbu
`price_observation.submitter_id` na uživatele, aniž by se reputace rozbila. Soukromí a výkon
se tu shodují. Váhu `L` to nemění — je snapshotovaná do `price_observation.submitter_kind`
při zápisu ceny, ne odvozená z toho, jestli `submitter_id` pořád ukazuje na živý účet.

```
A = (agree_w + 4) / (agree_w + disagree_w + 8)          přesnost, Bayesovské vyhlazení, start 0,5
E = min(1, ln(1+n_eff) / ln(51))                        zkušenost, saturuje (nejde nahromadit kvantitou)
T = min(1, dny_od_prvního_příspěvku / 30)               stáří účtu — proti sybilům
L = 0,15 anonym | 0,60 nový (< 30 dní) | 1,00 zavedený | 1,20 komunitně ověřený
P = exp(−0,35 · flag_w)                                 penalizace za systémové flagy

S = clamp( L · T · (0,25 + 0,75·A) · (0,40 + 0,60·E) · P , 0,02 , 1,20 )
```

- **Spodní mez 0,02, ne nula.** Škodič nedostane tvrdou nulu — jeho záznamy dál existují,
  ale jsou tiše přehlasovány. Nula je pozorovatelná zvenčí a provokuje k zakládání nových
  účtů; 0,02 vypadá jako normální (byť nedůvěryhodný) provoz.
- **Strop 1,20, ne neomezeně.** Nemá vzniknout kasta, jejíž hlas přebije všechny ostatní,
  ať je sebedůvěryhodnější.
- **`T` (stáří účtu)** je samostatná složka, ne jen součást `L` — i dokonale přesný nový účet
  nemá plnou váhu, dokud neprokáže, že není založený jen pro jednu kampaň.

## Váha jednoho cenového záznamu

```
w = S_autor · f_conf · f_evid · f_recency · f_group

f_conf    = 1 + 0,5 · Σ_j min(S_j, 1)      přes NEZÁVISLÉ potvrzovatele (strop 2,5)
f_evid    = 1,30 účtenka+OCR / 1,15 foto cedulky / 1,00 bez důkazu
f_recency = 0,5^(stáří_dní / 14)           jen pro "aktuální cenu", ne pro historii v grafu
f_group   = 3,0 autor je v mé skupině důvěry / 1,5 vzdálenost 2 kroky / 1,0 jinak
```

Rozlišení `f_evid` mezi „účtenka+OCR" a „foto cedulky" předpokládá, že schéma ukládá **druh
důkazu**, ne jen odkaz na fotku — až se bude psát fotka jako důkaz ceny (nedodělek MVP), viz
`docs/ai.md`, „Vazba na f_evid".

„Nezávislý potvrzovatel" = není ve stejné skupině důvěry jako autor a není v témže
detekovaném sybil klastru. Bez této podmínky je potvrzování triviálně zneužitelné (skupina
si vzájemně potvrzuje cokoliv).

## Agregace váženým mediánem, ne průměrem

Ceny mají těžké chvosty (překlep 129 Kč místo 12,90 Kč, záměna akční a běžné ceny). Vážený
průměr má bod zvratu 0 % — jediný extrém posune výsledek libovolně daleko. Vážený medián má
bod zvratu 50 %, takže menšina útočníků výsledek neovlivní vůbec, ne jen málo.

```
Seřaď záznamy podle unit_price. W = Σ wᵢ.
Vážený medián = nejmenší p_k, pro které Σ_{i≤k} wᵢ ≥ W/2
(při přesné rovnosti lineárně interpoluj mezi p_k a p_{k+1})
```

Doprovodně **Kishova efektivní velikost vzorku** `n_eff = (Σw)² / Σw²`, uložená v
`agg.price_current.n_eff` a zobrazovaná uživateli. Je to zároveň nejlepší veřejná obrana
proti manipulaci — je vidět, na kolika nezávislých hlasech cena skutečně stojí, ne jen kolik
řádků je v tabulce. Pod `n_eff < 2` se zobrazí „orientační údaj".

Národní cena (napříč obchody) je **medián mediánů** — nejdřív vážený medián uvnitř
provozovny, pak medián těchto mediánů přes provozovny. Jinak by jedna provozovna s 200
záznamy přebila 50 provozoven s jedním záznamem každá.

## Souhlas se určuje leave-one-out

```
med₋ᵢ = vážený medián ostatních záznamů (produkt, obchod, druh ceny) v okně ±7 dní,
        BEZ vlastního záznamu uživatele i
AGREE     |pᵢ − med₋ᵢ| ≤ max(0,05·med₋ᵢ; 1 Kč)
DISAGREE  |pᵢ − med₋ᵢ| >  max(0,15·med₋ᵢ; 3 Kč)
NEUTRAL   mezi tím
```

Leave-one-out je nutný — jinak osamělý přispěvatel vždy „souhlasí sám se sebou" (jeho
záznam by byl součástí mediánu, se kterým se porovnává) a vybuduje si reputaci na
neověřitelných datech.

## Proč žádné veřejné negativní hodnocení uživatelů

1. **Veřejné mínusy vytvářejí odvetné spirály a smečkování** — je to zdokumentovaně ten
   jeden mechanismus, kterým se jinak zdravé komunity mění v toxické.
2. **Snadno se zneužije k umlčení**: skupina lidí umlčí lokálního dodavatele nebo
   menšinový názor dřív, než si toho někdo všimne.
3. **Právní expozice pro sólo vývojáře**: veřejné negativní skóre je osobní údaj s
   reputačním dopadem — riziko sporů o difamaci, které si jeden člověk nemůže dovolit řešit.
4. **Není to ani potřeba.** Informace, kterou aplikace skutečně potřebuje ("je tahle cena
   správná?"), je odvoditelná z objektivní shody dat (leave-one-out souhlas výše) —
   "nemám rád tohohle člověka" k tomu nic nepřidává.

**Náhrada:**
- Veřejně jen **pozitivní** signály: „věřím mu" (`trust_edge`), počty v pásmech
  („důvěřuje mu 10+ lidí"), ne přesná čísla — snižuje to honbu za skóre.
- **Nesouhlas se vyjadřuje k faktu, ne k člověku**: „tato cena už neplatí" založí nový
  záznam, ne negativní hodnocení autora toho starého.
- **Systémové flagy (`user_flag`) jsou neveřejné, ale viditelné vlastníkovi účtu** vlídnou
  formou („některé tvé záznamy se liší od ostatních — nezaměnil jsi cenu za kus a za kilo?")
  s možností požádat o přezkum. Transparentní k dotyčnému, bez veřejného pranýře.
- **Absence důvěry je sama o sobě negativní signál** — tichý, neeskalující, ale ve výpočtu
  vah plně funkční (nikdo v žádné skupině důvěry ⇒ `f_group = 1,0`, žádný bonus).

Profil uživatele (`docs/soukromi.md`, „Profil uživatele a viditelnost") má proto zatím jen
NEAKTIVNÍ odkazy „Hodnocení systémem" a „Důvěra od přátel" — pojmenování je záměrně jiné než
prosté „hodnocení", ať je vidět, že jde přesně o tyhle dva mechanismy výš (`user_flag`
transparentní vlastníkovi, `trust_edge` jako veřejný pozitivní signál), ne o nový kanál pro
hodnocení člověka člověkem. Než budou skupiny důvěry a plný vzorec `S` napsané (další rozvoj),
odkazy nikam nevedou.

## Detekce zneužití

Všechny signály vedou na neveřejný `user_flag`, nikdy na veřejné označení:

| Signál | Kritérium | Poznámka |
|---|---|---|
| `BIASED` | medián znaménkových odchylek > 8 % přes ≥ 15 porovnání za 60 dní | systematické nadhodnocování/podhodnocování |
| `IMPOSSIBLE` | odchylka od kategorie > 6× MAD | **první výskyt bez penalizace** — překlepy se dějí |
| `TELEPORT` | dva záznamy > 5 km od sebe s implikovanou rychlostí > 130 km/h | počítá se VÝHRADNĚ ze souřadnic provozoven, žádné GPS uživatele (soukromí a bezpečnost si tu neodporují) |
| `BURST` | > 40 záznamů/hod nebo > 15 provozoven/den | měkký throttle, ne rovnou trest |
| `CLUSTER` (sybil) | ≥ 3 účty s podobnou aktivitou, vzájemnou důvěrou, registrací do 7 dní | uvnitř klastru se potvrzení SČÍTAJÍ jako jedno (`f_conf` bere maximum, ne součet) |
| `COMMERCIAL` | > 70 % záznamů se týká jedné značky při ≥ 20 záznamech | ruční přezkum |

## Obrana proti manipulaci obchodníků a řetězců

1. Nejvýš 1 záznam / účet / produkt / obchod / den **na druh ceny** (`uq_price_observation_
   submitter_kind_per_day`) — strop je tedy nově 5 řádků denně na jednu dvojici produkt/obchod
   (počet hodnot `PriceKind`), protože u regálu bývá cenovka dvojitá/trojitá (běžná + klubová +
   množstevní) a appka je zapisuje jedním `submitObservations`; pořád ale platí „jedna cena
   JEDNOHO druhu od jednoho člověka na jednu cenovku a den".
2. Strop 30 % váhy jedné skupiny důvěry v agregátu (přebytek se renormalizuje).
3. Účty s rolí dodavatele/řetězce nesmí zadávat komunitní observace ve vlastních kategoriích.
4. **Legitimní kanál místo podvádění**: oficiální ceny přes `official_price_feed`, zobrazené
   odděleně a nikdy nevstupující do komunitního agregátu.

## Odstupňování přístupu (T0–T4)

Princip: **anonym vidí hodnotu, ne objem.** Základní veřejná funkce (kolik stojí chleba
v okolí) zůstává volná, protože to je poslání projektu a jediná cesta k organickému růstu.
Zavírá se hloubka, personalizace a obsah vytvořený komunitou (recenze).

| | Podmínka | Získává |
|---|---|---|
| T0 anonym | — | Vyhledání, aktuální cena, historie 7 dní, hvězdičky bez textů. Může zadat záznam (váha ×0,15, max 3/den). |
| T1 registrovaný | ověřený e-mail | Plné grafy, texty recenzí, sledování a alerty |
| T2 přispěvatel | ≥5 záznamů nebo 1 recenze, ≥7 dní | Bez limitů, skupiny důvěry, ceny vážené mou skupinou |
| T3 důvěryhodný | `S ≥ 0,7`, ≥30 dní | Editace bez moderace, nominace dodavatelů, pozvánky |
| T4 moderátor | ruční | Moderace, přezkum flagů, schvalování dodavatelů |

Limity patří do konfigurace (budoucí `core.access_policy`), ne natvrdo do kódu — v MVP
nastavené velkoryse, protože **studený start je větší riziko než parazitování na datech**:
prázdná appka nikoho nezaujme, přísná reciprocita od prvního dne ji zabije dřív, než ji
někdo stihne ocenit.

> **Zaznamenaná odchylka od tabulky:** `priceHistory` dnes dává anonymovi 90 dní
> historie (`app.history.anonymous-max-days` v `application.yml`), ne 7 dní jako tabulka výš.
> Mechanismus odstupňování existuje (`PriceHistoryService` ořezává okno podle přihlášení),
> jen je práh zatím nastavený velkoryse ze stejného důvodu jako limity výš — je to úmysl,
> ne rozjetí kódu s dokumentem. Zpřísnit na 7 dní je jen změna jedné konstanty, až/pokud
> bude důvod.

> **T1 „texty recenzí" je od dalšího rozvoje implementované doslovně** —
> `ProductReviewService.reviewsFor` vrací anonymovi `loginRequired: true` a prázdné `items`
> (ale reálný `totalCount`), přihlášenému plný text. Protože appka nemá stav „neověřený
> e-mail" (registrace přes OTP kód v e-mailu ho ověří rovnou), podmínka T1 je v kódu prostě
> „přihlášený", žádná samostatná kontrola verifikace navíc.

## Práh důvěry pro zveřejnění nového záznamu (MVP)

Nový obchod nebo zboží od nedůvěryhodného autora se hned nezveřejní všem — je vidět jen jemu,
dokud ho nepotvrdí víc přispěvatelů (`TrustLevelService`, `app.trust.*`). Důvod je stejný jako
u prahu T2 výš (`≥5 záznamů nebo 1 recenze, ≥7 dní`), jen implementovaný dřív a jednodušeji —
`isTrusted` dnes počítá jen se záznamy cen, recenze (i když už existují) do něj zatím
nevstupují, protože chybí plný vzorec `S`:

```
isTrusted(user) = user.createdAt < now − app.trust.min-account-age-days
                  AND user.observationCount ≥ app.trust.min-observations
```

Výchozí `min-account-age-days = 7`, `min-observations = 5` — schválně stejná čísla jako T2,
je to jeho MVP aproximace, ne nezávisle vymyšlený práh. Až přibude plný vzorec `S`
(další rozvoj), tahle funkce se nahradí `S ≥ práh_T2`, ne zdvojí vedle sebe.

Stejný práh platí i pro založení zboží nad OFF snapshotem (`createProductFromOff`,
`OffProductCatalogService`) — vlastní EAN je dost silná identifikace zboží (na rozdíl od
druhové položky níže), ale autor OFF ověřený není, takže i tady nedůvěryhodný účet dostane
`DRAFT`, ne rovnou `ACTIVE`.

**`observationCount` je čítač na účtu (`auth.app_user.observation_count`), ne `COUNT(*)` nad
`core.price_observation`** — ze stejného důvodu jako čítače s útlumem v úvodu dokumentu:
`submitter_id` se po 180 dnech nuluje (`docs/soukromi.md`), takže počítání přes historii by
zavedenému uživateli po pauze tiše sebralo důvěru, kterou si dřív vybudoval. Roste **+1 za
zápis (dávku)**, ne za řádek — `submitObservations` může uložit až 5 cen z jedné cenovky
jedním voláním, takže počítat po řádcích by práh `min-observations = 5` šlo naplnit jediným
odesláním u jednoho regálu místo pěti nezávislých příspěvků.

Ze stejného důvodu se anonymní zápisy do počtu potvrzení vůbec nepočítají
(`countDistinctContributorsExcluding`/`countDistinctProductContributorsExcluding` a jejich
`…Batch` varianty, `PriceObservationRepository`: `count(DISTINCT submitter_id) … WHERE
submitter_id IS NOT NULL`) — dřív se každá anonymní observace počítala jako samostatný
přispěvatel (žádný `submitter_id` k odlišení), po zavedení dávky by jedno anonymní odeslání tří
druhů ceny odemklo DRAFT zboží/PENDING obchod jedním kliknutím. Anonymní identita se navíc
nedá přiřadit k účtu vůbec, takže by ji šlo vydávat za libovolný počet různých přispěvatelů —
proto DRAFT/PENDING odemyká výhradně shoda **registrovaných** uživatelů. Práh
`app.catalog.draft-confirmations` (výchozí 3) se tím zpřísnil — k pozorování po spuštění bety,
jestli u studeného startu bezkódové zboží neuvízne v DRAFTu příliš dlouho a jestli produkční
práh nesnížit na 2 (`docs/nasazeni.md`, „Zbývá").

Efekt prahu:
- **Nad prahem** — nový záznam je hned vidět všem, se štítkem "neověřeno" (`verified_at`
  je `NULL`, dokud neproběhne konsolidační job, viz `docs/datovy-model.md`).
- **Pod prahem** — v OTEVŘENÉM hledání (`searchProducts`, `ProductSearchRepositoryImpl`) ho
  vidí jen autor, ať nepotvrzený šum nezahlcuje běžné hledání ostatních. CÍLENĚ ho ale najít
  musí jít i JINÝM přispěvatelům — jinak by `app.catalog.draft-confirmations` (výchozí 3)
  DALŠÍCH lidí nemělo jak nastartovat: ať kód naskenuje jiný člověk (`productByCode`), nebo
  narazí na stejné jméno při zakládání podobného zboží a vybere si nabídnutou existující
  položku místo duplicity (`productSuggestions` → `product(id)`, `ProductFormViewModel
  .useExisting`/`product-form.ts`), DRAFT je proto v `product`/`productByCode`
  (`ProductGraphQlController.isVisible`, predikát `status IN (ACTIVE, DRAFT)`) vidět
  KAŽDÉMU — leave-one-out se řeší jinde: `promoteIfConfirmed` počítá jen potvrzení od JINÝCH
  uživatelů než autora (`countDistinctContributorsExcluding`), jinak by si zakladatel odemkl
  vlastní záznam sám třemi vlastními zápisy.

Autor postup k prahu vidí přímo v UI, ne jen jako "čeká na potvrzení" bez kontextu — výpis
"Moje příspěvky" (`MyContributionsService`, web `/my`, mobil `ui/contributions/
MyContributionsScreen.kt`) k němu dopočítá `PublicationStatus.confirmationsReceived` (dávkově,
stejné leave-one-out přes `countDistinctProductContributorsExcludingBatch`/
`countDistinctContributorsExcludingBatch`) a `confirmationsRequired`, takže appka umí ukázat
"zatím 1 ze 3". Prahy samotné (`app.trust.*`, `app.catalog.draft-confirmations`) zůstávají jen
tady a v `application.yml`, výpis je čistě čtecí vrstva nad nimi.

## Nahlášení záznamu (MVP) — hlasuje se o faktu, ne o člověku

`core.record_flag` (`RecordFlagService`) je MVP implementace principu z "Proč žádné
veřejné negativní hodnocení uživatelů" výš, jen aplikovaná na KATALOGOVÉ záznamy (zboží,
obchody), ne na cenové spory: nahlášení cílí na `(recordType, recordId)`, nikdy na autora.
Kdo záznam založil, se z API ven nedostane o nic snáz, než dřív — nahlášení jen řekne "tenhle
konkrétní záznam je podezřelý", stejně jako `user_id` u `product_review` slouží výhradně
k vynucení "jeden hlas na člověka" a jinak z DB nikam neuniká (`docs/soukromi.md`).

Po dosažení `app.moderation.flags-to-hide` (výchozí 3) RŮZNÝCH nahlášení se záznam skryje
(`hidden_at`) a čeká na přezkum — vidí ho dál jen autor, se stejným důvodem jako u DRAFT/
PENDING výš (autor musí vědět, co se s jeho příspěvkem děje). Druhé nahlášení od téhož
člověka nic nezmění (`uq_record_flag_user`, `INSERT ... ON CONFLICT DO NOTHING`) — bez týhle
pojistky by šlo záznam skrýt jedním účtem opakovaným klikáním.

**Fotky (`RecordType.PHOTO`, `core.media`) mají vlastní, mnohem nižší práh**
(`app.moderation.photo-flags-to-hide`, výchozí **1**) — stejný mechanismus, jiné číslo.
Důvod je asymetrie cen chyby, ne nedůvěra v komunitu: u katalogového textu je cena falešně
pozitivního nahlášení vysoká (zmizí správný záznam, na kterém visí historie cen) a cena
falešně negativního nízká (nesmyslný název nikoho nepoškodí, jen matí). U fotky je poměr
opačný — smazaná dobrá fotka se nahraje znovu za deset vteřin, zatímco přehlédnutý nevhodný
obrázek je vážný problém a kapacita moderace jednoho člověka je reálný limit projektu
(`docs/soukromi.md`, "Otevřená rizika"). Fotka je proto vidět hned po nahrání (stejně jako
u důvěryhodného autora zboží/obchodu), ale jediné nahlášení ji rovnou skryje.

**Text recenze (`RecordType.REVIEW`) má práh mezi fotkou a katalogem**
(`app.moderation.review-flags-to-hide`, výchozí **2**) — nahlašuje se TEXT
(`core.product_review.id`, konkrétní recenze, ne produkt jako celek ani hodnocení samo), ne
autor. Volný text je rizikovější než katalogové pole (název zboží nejde snadno urazit ani
spamovat, volný text ano), ale míň rizikový než obrázek — proto práh mezi fotkou (1) a
zbožím/obchodem (3). Recenze skrytá po nahlášení zůstává vidět autorovi ve výpisu „Moje
příspěvky" se štítkem „skryto", ať ví, že a proč zmizela z produktu.

**Plánovaný strojový předfiltr fotek (`docs/ai.md`) nikdy nenahrazuje hlas člověka ani sám nesahá
na `hidden_at`** — jen řadí frontu k přezkumu, stejně jako zbytek téhle sekce hlasuje o
záznamu, nikdy o člověku.

## Moderace (MVP) — nástroj pro T4

Odstavec výš popisuje, jak se záznam SKRYJE (`flagRecord`/`RecordFlagService`) — tenhle popisuje,
co se s ním stane dál, protože „čeká na přezkum" dřív nemělo kým se naplnit. Implementace:
`ModerationService`/`ModerationGraphQlController` (backend), stránka `/moderation` (jen web —
nástroj provozovatele, ne appky, mobil ji nemá).

- **Kdo je moderátor**: sloupec `auth.app_user.is_moderator`, nastavuje se ručně SQL příkazem na
  serveru (`docs/nasazeni.md`) — žádné UI na jmenování, odpovídá „T4 | ruční" v tabulce výš.
  Promítne se do JWT autorizace (`ROLE_MODERATOR`) nejpozději do 60 s (TTL cache v
  `JwtAuthenticationFilter`, stejný mechanismus jako u `token_version`).
- **Fronta** (`flaggedRecords`) vypíše nevyřízená nahlášení (`core.record_flag.resolved_at IS
  NULL`), seskupená podle záznamu, včetně skrytého obsahu — moderátor vidí to, co `hidden_at`
  schovává ostatním (predikáty viditelnosti v `ProductGraphQlController`/`StoreGraphQlController`/
  `MediaService`/`MediaController` mají navíc větev `|| viewer.moderator()`).
- **`resolveFlags(recordType, recordId, resolution)` je jediná cesta zpět**: `DISMISSED` vrátí
  `hidden_at` na `NULL` (dřív `RecordFlagService.hideRecord` byl jednosměrný — chybné nahlášení
  bylo trvalé), `UPHELD` skrytí potvrdí nebo ho rovnou nastaví, i když automatický práh
  `app.moderation.flags-to-hide` ještě nebyl dosažen — moderátorovo rozhodnutí je silnější než
  práh. Po vyřízení se stará nahlášení nepočítají znovu do prahu (`RecordFlagRepository.
  countByRecordTypeAndRecordIdAndResolvedAtIsNull`), jinak by jediný nový hlas skryl odkrytý
  záznam okamžitě zpátky.
- **Ceny nejde nahlásit komunitně** — `core.record_flag` míří jen na katalogové záznamy (viz
  „Nahlášení záznamu" výš, „ne na cenové spory"). Moderátor k vadné ceně přistupuje přímo přes
  autora/zboží/obchod (`moderationObservations`) a zamítá ji (`setObservationRejected` →
  `ObservationStatus.REJECTED`), což vždy zařadí dotčenou buňku do `agg.recompute_queue`
  (`RecomputeReason.MODERATION`) — bez toho by zamítnutá cena zůstala v grafu ještě několik dní.
- **Pozastavení účtu** (`setUserSuspended`, docs/podminky-uziti.md, „Ukončení a vyloučení") —
  `AppUserStatus.SUSPENDED` + inkrement `token_version` + revokace refresh tokenů
  (`RefreshTokenService.revokeAllForUser`). Pozastavený účet se přestane autentizovat nejpozději
  do 60 s a nedostane nový přihlašovací kód (`OtpService` kontroluje status před `requestOtp`
  i `verifyOtp`) — pozastavení se tak nedá obejít novým přihlášením.
- **Kdo nahlásil zůstává skryté i moderátorovi** (`record_flag.user_id` z API nejde ven,
  `docs/soukromi.md`) — moderátor vidí jen počty a texty důvodů. **Kdo záznam založil/nahrál**
  (`authorPublicUid`/`authorHandle` na `FlaggedRecordItem`/`ModerationObservationItem`) vidí
  naopak jen moderátor, jinak nemá jak uplatnit „Ukončení a vyloučení" z podmínek užití — je to
  jiná informace se schválně jiným pravidlem, viz `docs/soukromi.md`.

## Hodnocení kvality zboží a text recenze

Hvězdičky 1–5 (5 nejlepší) povinně, text recenze volitelně (max 1000 znaků), bez skupin
důvěry — implementace `core.product_review`, popis tabulky v `docs/datovy-model.md`. Hvězdičky
původně byly školní známka (1 nejlepší, 5 nejhorší) — testování ukázalo, že se čte matoucně
(lidé mají různý zvyk, jak známkování číst), takže se škála otočila na hvězdičky, kde víc =
líp; sloupec `grade` se přejmenoval na `stars`. Vědomá zjednodušení oproti zbytku téhle
stránky:

- **Průměr hvězdiček se NEVÁŽÍ reputací `S`** — dnes je to prostý aritmetický průměr přes
  všechny hodnocení (`AVG(stars)`), protože `S` samo je zatím jen složka `L` (viz úvod
  dokumentu) a vážit průměr neúplným vzorcem by budilo falešný dojem přesnosti. Až bude `S`
  implementované celé, patří sem vážený průměr stejnou logikou jako vážený medián cen výše.
- **Práh `min-ratings-for-badge = 3`** (`app.quality.min-ratings-for-badge`) — pod tímhle
  počtem hodnocení klienti (mobil i web) zobrazí hvězdičky jako „orientační", obdoba pravidla
  `n_eff < 2` u cen. Jedno naštvané (nebo jedno nadšené) hodnocení tak neurčí veřejný obrázek
  produktu, dokud se nesejde víc hlasů. Text recenze tenhle práh nemá — je vidět hned (za T1
  gatingem výš), stejně jako fotka je vidět hned po nahrání.
- **Text vyžaduje existující hodnocení hvězdičkami** (`REVIEW_REQUIRES_RATING`) — hvězdičky se
  zadávají přes `rateProduct` a svůj vlastní řádek si založí samy (upsert), text se k
  existujícímu řádku jen připojuje. Smazání textu hvězdičky nemění.

**Vztah k „žádné veřejné negativní hodnocení uživatelů" výše:** hodnocení je o VĚCI
(produktu z katalogu), ne o ČLOVĚKU, takže s pravidlem nekoliduje — ale text recenze je
volný jazyk, ne jen číslo, takže riziko urážlivého nebo napadajícího textu je reálnější než
u samotných hvězdiček. Bezpečnostní záklopka je stejná jako u zbytku katalogu: nahlašování
(`RecordType.REVIEW`, „Nahlášení záznamu" výš) cílí na TEXT, nikdy na autora, a moderátor ho
umí skrýt/schválit stejným tokem jako zboží nebo fotku. Riziko je blízké, ne stejné: až při
dalším rozvoji přibudou lokální dodavatelé (`core.supplier`, `core.supplier_offer`), recenze
na výrobek malého farmáře bude fakticky veřejné hodnocení konkrétního člověka — se všemi
důsledky popsanými výš (odvetné spirály, právní expozice). Rozhodnutí „hodnotí se jen zboží
z katalogu, ne nabídky dodavatelů" je proto potřeba **znovu vědomě potvrdit nebo přepracovat
před založením `core.supplier`**, ne jen automaticky rozšířit stejný mechanismus.

## Zboží bez čárového kódu

Ne všechno zboží má EAN — účtenka z pekárny umí napsat jen "pečivo za 45 Kč", podniková
prodejna zemědělského družstva na vsi často nemá vůbec žádný pokladní systém s čárovými kódy.
Takový zápis má menší cenu než zápis s EANem (žádný jednoznačný identifikátor napříč obchody),
ale je lepší mít ho s nižší důvěryhodností než ho odmítnout úplně — proto `createProduct` bez
`code` založí **druhovou položku** (`core.product.is_generic`, `ProductCatalogService`):
sdílený "koš" pro bezkódové zápisy stejného druhu zboží ("Chléb konzumní", "Brambory
konzumní"), ne nový záznam pro každý jednotlivý zápis.

**Nová položka vzniká jako `status = DRAFT`**, dokud ji nepotvrdí aspoň
`app.catalog.draft-confirmations` (výchozí 3) různých **registrovaných** přispěvatelů —
počítáno jako `COUNT(DISTINCT submitter_id) … WHERE submitter_id IS NOT NULL`
(`PriceObservationRepository.countDistinctProductContributorsExcluding`); anonymní zápisy
(`submitter_id` vždy `NULL`, docs/soukromi.md) se do prahu nepočítají vůbec — viz „Reputační
skóre — čítače s exponenciálním útlumem" výš. Jakmile práh padne, `PriceObservationService.submit()`
položku po zápisu ceny rovnou překlopí na `ACTIVE` (`ProductCatalogService.promoteIfConfirmed`) —
žádný plánovač navíc.

**Confidence buňky (`agg.price_current.confidence`) je pro druhovou položku zastropovaná na
`MEDIUM`**, bez ohledu na `n_eff` (`PriceAggregationService.confidenceFor()`) — chybějící EAN
je slabší důkaz totožnosti zboží než u položky s kódem, i kdyby se na ceně shodlo hodně lidí.

**Tohle vědomě NENÍ multiplikativní faktor váhy jednotlivého záznamu** (uvažovaný a zavržený
`f_catalog`). Agregace běží vždy uvnitř buňky `(produkt, obchod)` a
faktor odvozený z produktu by byl v celé buňce konstantní — jak vážený medián, tak Kishovo
`n_eff = (Σw)²/Σw²` jsou vůči přeškálování **všech** vah v buňce stejným číslem invariantní,
takže by `agg.price_current` vyšlo bit po bitu stejně jako bez něj. Efekt "bezkódové zboží je
méně důvěryhodné" proto patří tam, kde skutečně něco mění — do stropu confidence a do statusu
DRAFT/ACTIVE, ne do váhy záznamu.
