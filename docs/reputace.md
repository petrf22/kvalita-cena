# Reputace a váhy

Jedno zadání určuje celý tento systém: komunita má být pozitivní, ne toxická. To vylučuje
veřejné negativní hodnocení uživatelů, i když by bylo technicky nejjednodušší — viz níže.

> **Stav v etapě 1 (MVP):** implementovaná je zatím jen složka `L` (anonym 0,15 / registrovaný
> 1,00) a vážený medián v agregaci. Zbytek vzorce (přesnost, zkušenost, stáří účtu, penalizace,
> detekce zneužití, skupiny důvěry) je popsaný tady jako cílový stav pro etapu 2/3, aby ladění
> mělo od začátku jedno místo, kam patří — ne aby se prahy rozsely po kódu.

## Reputační skóre — čítače s exponenciálním útlumem

Skóre se nepočítá z historie jednotlivých událostí, ale jako průběžně aktualizovaný čítač:

```
X ← X · 0.5^((t_now − t_prev) / H) + Δ
```

kde `H` je poločas rozpadu. Výpočet je O(1) a nepotřebuje log událostí — což je to, co
umožňuje po 180 dnech smazat vazbu `price_observation.submitter_id` na uživatele (viz
`soukromi.md`), aniž by se reputace rozbila. Soukromí a výkon se tu shodují.

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

1. Nejvýš 1 záznam / účet / produkt / obchod / den (`uq_price_observation_submitter_per_day`).
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
