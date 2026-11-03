# Worker účtenek

Pull worker, který běží **u provozovatele na PC** proti lokální Ollamě. Vyzvedává si ze
serveru naskenované účtenky, čte je a vrací vytěžený text jako **návrh**. Cenu zapíše až
uživatel potvrzením v appce.

Rozhodnutí kolem téhle funkce jsou v těchto dokumentech:

- [`docs/ai.md`](../../docs/ai.md), oddíl „Účtenky" (topologie, prahy, autentizace)
- [`docs/soukromi.md`](../../docs/soukromi.md), oddíl „Účtenky"
- [`docs/rozvoj.md`](../../docs/rozvoj.md)

Stačí standardní knihovna Pythonu 3.12, žádné `pip install` není potřeba.

## Spuštění

```bash
ollama pull glm-ocr:bf16                        # jednorázově (licence MIT)

export KC_API_URL=https://api.kvalitacena.cz    # lokálně http://localhost:8080
export KC_WORKER_TOKEN="$(cat ~/.config/kvalitacena-worker-token)"   # viz „Token" níž
python3 tools/uctenky/worker.py                 # smyčka: když je fronta prázdná, čeká 5 min
python3 tools/uctenky/worker.py --once          # jedna dávka a konec (cron / systemd timer)
```

Další volby:

- `--model`: jiný OCR model. Před nasazením ověř licenci, pravidlo projektu jsou jen svobodné
  licence.
- `--ollama`: adresa Ollamy.
- `--batch`: kolik účtenek si worker vezme najednou.
- `ARCHIVE_DIR=/cesta`: stáhne obrázky účtenek, u kterých uživatel **zvlášť souhlasil**
  s ponecháním pro zlepšení rozpoznávání. Stáhne je dřív, než je server po retenci smaže.
  Archiv drž mimo git.

Na čekající účtenky upozorní server e-mailem (`RECEIPT_NOTIFY_EMAIL`, `docs/nasazeni.md`),
takže worker stačí spouštět ručně s `--once`. Zkratka pro to je `tools/uctenky/run-worker.sh`:
nastaví obě proměnné výš (`KC_API_URL` jde přebít zvenku) a předá workeru další argumenty.

Když worker neběží, účtenky jen čekají ve frontě a appka funguje beze změny. Worker, který
spadne uprostřed práce, nevadí: lease po 15 minutách vyprší a účtenka se vrátí do fronty.

### Token

Worker se přihlašuje strojovým tokenem, ne účtem uživatele. V databázi je uložený jen jeho
SHA-256. Token proto vzniká na PC a na server jde jen jeho otisk; samotný token PC neopustí.

Na PC:

```bash
TOKEN="kcw_$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
echo "$TOKEN" > ~/.config/kvalitacena-worker-token && chmod 600 ~/.config/kvalitacena-worker-token
printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1   # otisk pro INSERT níž
```

Na produkčním serveru (přes ssh, v adresáři s `compose.prod.yaml`):

```bash
docker compose -f compose.prod.yaml exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d kvalitaacena' <<'SQL'
INSERT INTO auth.api_token(name, token_hash, scope)
VALUES ('domaci-pc', decode('OTISK', 'hex'), 'RECEIPT_WORKER');
SQL
```

Dvě věci, na kterých to padá: role `postgres` na produkci neexistuje (uživatele nastavuje
`POSTGRES_USER` z `.env` uvnitř kontejneru, proto `sh -c`), a bez `-T` skončí `exec`
s heredocem na `cannot attach stdin to a TTY-enabled container`. Lokálně stačí
`docker compose exec -T postgres psql -U postgres -d kvalitaacena` se stejným heredocem.

Token zneplatníš příkazem `UPDATE auth.api_token SET revoked_at = now() WHERE name = 'domaci-pc';`.
Změna se projeví do 60 s.

## Jak se účtenka čte

1. **OCR**: [`ocr.py`](ocr.py). Model převádí **výhradně obrázek na text**. Každá část dlouhé
   účtenky se čte zvlášť.
2. **Spojení částí**: `parse.merge_segments`. Mobil fotí dlouhou účtenku po dílech
   s překryvem, takže řádky zopakované na hranici dvou dílů se zahodí.
3. **Struktura z textu**: [`normalize.py`](normalize.py), [`lines.py`](lines.py)
   a [`profiles/`](profiles/). Vzniká deterministicky, regexy. Každý řetězec má vlastní profil
   (hlavička, patička, vymezení bloku položek); co profil nemá, jde přes obecný profil.
4. **Kontrolní součet**: součet položek a slev se porovná s vytištěnou částkou „Celkem"
   (`totalMatches`). Když nesedí, výsledek se přesto pošle. Uživatel řádky potvrzuje jednotlivě
   a varování zůstane v `extraction` u úlohy.
5. **Výsledek**: [`result.py`](result.py) sestaví výsledek. Slevy připojí k položce, které se
   týkají, a klubovou slevu pozná podle textu. Obrázek bez částek dostane stav `NOT_A_RECEIPT`.
   Když jsou na obrázku částky, ale žádná položka, stav je `UNREADABLE`.
6. **Čitelný název**: [`pretty.py`](pretty.py) a [`ai_pretty.py`](ai_pretty.py). Ke každému
   řádku přidá `labelDisplay` („ROHLÍK43GR" → „Rohlík 43g") a vodítko gramáže pro párování.
   Podrobně „Čitelné názvy" níž.

## Čitelné názvy

Účtenka tiskne „NUGETA ARASÍDOVÁ340G", uživatel chce vidět „Nugeta arašídová 340g". Čitelný
název je **jen pro oči a hledání**: server ho uloží vedle syrového tisku
(`receipt_line.label_display`), ale mapování označení → zboží se dál učí podle syrového tisku.
Postup:

1. **Ruční přepis celého označení**: [`slovnik/nazvy.tsv`](slovnik/nazvy.tsv)
   (`označení<TAB>název[<TAB>řetězec]`). Označení se porovnává jako `core.norm_label`, tedy bez
   diakritiky, interpunkce a velikosti písmen. Zdroj `DICT`.
2. **Pravidla a zkratky**: rozdělí slepené tokeny („BÍLY330G" → „BÍLY 330G") a zapíše jednotky
   kompaktně česky („0,5l", „43g", „3,9%"). Zkratky a opravy slov bere ze slovníku
   [`slovnik/zkratky.tsv`](slovnik/zkratky.tsv) (`zkratka<TAB>rozepsání[<TAB>řetězec]`,
   klíč bez diakritiky; „SELS." → „Selské", „RIZKY" → „řízky"). Slova mimo slovník převede na
   malá písmena, první slovo velkým. Zdroj `RULES`. Popisek, který obchod tiskne malými
   písmeny (TAMDA), zůstane, jak je.
3. **Lokální model** (`--pretty-model`, výchozí `qwen2.5:7b-instruct`, Apache-2.0; prázdné
   = vypnuto): dostane jen názvy se slovy, která slovník nevyřešil, jednou dávkou za účtenku.
   Smí doplnit diakritiku, opravit velikost písmen a rozepsat zkratku s tečkou.
   **Každý výstup projde kontrolou** `ai_pretty.accept`: stejný počet slov, čísla a jednotky
   beze změny, písmena beze změny kromě PŘIDANÉ diakritiky, zkratka smí jen pokračovat
   („sels." → „Selské"). Co neprojde, se zahodí (worker to zapíše do logu) a platí verze
   z pravidel. Zdroj `AI`. Výpadek modelu není chyba účtenky.

Slovník je v gitu a worker ho čte před každou dávkou. Doplňuje se ručně, nebo přes
`curate.py` (níž). Na offline ladění vypíše `replay.py` čitelné názvy pod rozborem
(`--pretty-model qwen2.5:7b-instruct` přidá i model).

## Návrh kategorie a jednotky

Když řádek nejde spárovat a uživatel zakládá nové zboží, formulář má mít předvyplněnou
kategorii a jednotku gramáže, aby stačilo zkontrolovat a uložit. Worker k tomu ke každému
řádku přidá návrh lokálního modelu (`ai_classify.py`, `--classify-model`, výchozí
`qwen2.5:14b-instruct`, Apache-2.0; prázdné = vypnuto):

- `categoryHint` je slug kategorie appky. Seznam s cestami česky si worker stáhne jednou za
  dávku z `GET /api/worker/receipts/categories` (stejný token).
- `unitHint` je jen rozměr gramáže (`G`/`KG`/`ML`/`L`). Číslo model nedodává nikdy, to umí
  jen vytištěné označení.

Výstup projde kontrolou `ai_classify.accept`, která zahodí:
- slug mimo seznam,
- jinou jednotku,
- jednotku u váhového zboží,
- jednotku, která odporuje vytištěné gramáži (hmotnost proti objemu).

Server totéž ověří znovu a uloží do `ai.receipt_line_hint`. Návrh modelu má nejnižší
přednost: před ním je vytištěná gramáž a kategorie, na které se shodne podobné zboží
v katalogu (docs/ai.md, „Návrh kategorie a jednotky"). Výpadek modelu není chyba účtenky.

Offline: `replay.py … --classify-model qwen2.5:14b-instruct --categories kategorie.json`
vypíše návrh za čitelným názvem. Soubor `kategorie.json` je uložená odpověď endpointu výš.

## Kurátorství

Spárovat každé označení musí dnes uživatel u své účtenky. `curate.py` to dovoluje
provozovateli udělat najednou pro všechny účtenky. Ukáže nespárovaná označení **seskupená
podle řetězce**: syrový tisk, čitelný název, počet výskytů a den, kdy se objevilo naposledy.
Účtenku ani uživatele neukáže (`docs/soukromi.md`, „Účtenky"). U každého označení vypíše
nejpodobnější zboží v rozsahu řetězce.

```bash
tools/uctenky/run-curate.sh                    # produkce, token z ~/.config/kvalitacena-curator-token
tools/uctenky/run-curate.sh --chain 1          # jen jeden řetězec (id)
KC_API_URL=http://localhost:8080 KC_CURATOR_TOKEN=kcw_… python3 tools/uctenky/curate.py
```

Příkazy (`?` je vypíše):

- `Enter` nebo číslo: spáruje označení s kandidátem. Mapování platí hned (`ACTIVE/OPERATOR`)
  a nespárované řádky ostatních účtenek i strojové odhady dostanou návrh. Cenu za nikoho
  nezapisuje, tu dál potvrzuje vlastník účtenky.
- Když označení už míří na jiné zboží, `curate.py` se zeptá, jestli ho přepsat. Tak se opravuje
  špatně naučené označení.
- `h [dotaz]`: hledat zboží jinak.
- `e <název>`: opravit čitelný název. Oprava se zapíše do `slovnik/nazvy.tsv`.
- `z ZKR=rozepsání`, resp. `zr ZKR=rozepsání`: přidá zkratku do `slovnik/zkratky.tsv` pro
  všechny řetězce, resp. jen pro tento řetězec.
- `s`: přeskočit, `q`: konec.

**Úpravy slovníku commitni do gitu.** Worker ho čte před každou dávkou, takže oprava platí už
při normalizaci dalších účtenek. `--pretty-model qwen2.5:7b-instruct` dočistí názvy modelem
i tady.

Token kurátora vzniká stejně jako token workeru (viz „Token" výš), jen s jiným rozsahem
a souborem:

```bash
TOKEN="kcw_$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
echo "$TOKEN" > ~/.config/kvalitacena-curator-token && chmod 600 ~/.config/kvalitacena-curator-token
printf '%s' "$TOKEN" | sha256sum | cut -d' ' -f1
# na serveru: INSERT INTO auth.api_token(name, token_hash, scope)
#             VALUES ('domaci-pc-kurator', decode('OTISK', 'hex'), 'LABEL_CURATOR');
```

Token workeru na `/api/curator` nedosáhne a naopak. Stroj s OCR tak katalog měnit nemůže.

## Dvě věci, které vypadají jako detail, a nejsou

**Strukturu nesmí tahat jazykový model.** Ověřili jsme to na reálné účtence Penny: druhý
průchod přes `qwen2.5:14b-instruct` zahodil řádek `3.000 ks x 36.90 Kč /ks`. Zůstalo jen
zaplacených 110,70, tedy trojnásobek ceny z regálu. Jindy tentýž model přejmenoval zboží,
a to kontrolní součet nezachytí. Proto model dělá jen OCR (podrobně
[`normalize.py`](normalize.py)). Jedinou výjimkou je kosmetika čitelného názvu: ta nemění
strukturu ani syrový tisk a hlídá ji deterministická kontrola (`ai_pretty.accept`).

**U váhového zboží je cena za kg/l, nikdy zaplacená částka.** Z řádku
`0,302 kg x 289,00 Kč/kg 87,30` jde na server cena 289,00 za kg.

## Soukromí

Z patičky účtenky parser vezme jen datum, čas a celkovou částku. Číslo karty, autorizační
kód, čísla pokladny ani věrnostní údaje se na server nevrátí. Hlídají to testy. Jedinou
výjimkou je adresa prodejny COOP z bloku platebního terminálu (`profiles/coop.py`,
`docs/soukromi.md`, „Účtenky“).

Obrázky na serveru navíc uživatel před odesláním maskuje na zařízení.

## Ladění účtenky

Worker si nic neukládá. Když u účtenky hlásí varování (`nerozpoznaný tvar řádku`, chybí
celková částka nebo datum), zdroj se dohledá takhle:

1. **ID účtenky** je v řádku nad varováními: `!! účtenka <ID>: <status>, … profil …`.
2. **Obrázek**:
   - Vlastní účtenka: web `/receipts/<ID>` (nebo mobil). Obrázky jsou tam do 30 dní po
     zpracování (`app.receipt.image-retention-days`). Ulož je do `uctenky/<ID>/00.jpg`,
     `01.jpg`…, tedy shora dolů.
   - Účtenka se souhlasem s archivací: `ARCHIVE_DIR=uctenky tools/uctenky/run-worker.sh`
     stáhne obrázky do `uctenky/<ID>/`.
   - Token workeru zpracovanou účtenku bez souhlasu s archivací nevydá
     (`ReceiptWorkerService.image`). Cizí účtenku bez souhlasu si provozovatel ponechat
     nesmí (`docs/soukromi.md`, „Účtenky").
3. **OCR text** je na serveru v `ai.receipt_job.raw_text`, ale jen když se našel řádek
   s celkovou částkou (`parse.server_text`). Jinak je potřeba přečíst obrázek znovu. Výsledek
   vyjde stejně jako ve workeru, protože OCR běží s nulovou teplotou a pevným seedem.

Přehrání bez serveru a bez tokenu:

```bash
python3 tools/uctenky/replay.py --save-ocr uctenky/123/*.jpg   # OCR + rozbor, uloží 00.ocr.txt…
python3 tools/uctenky/replay.py uctenky/123/*.ocr.txt          # jen rozbor, bez Ollamy
python3 tools/uctenky/replay.py --profile albert uctenky/123/*.ocr.txt
python3 tools/uctenky/replay.py --json uctenky/123/*.ocr.txt   # celý dokument
```

Díly se předávají v pořadí shora dolů. Obrázky stažené z webu nebo archivu mají jako
název UUID a glob je seřadí abecedně, tedy náhodně vzhledem k pořadí na účtence. Pak je
vyjmenuj ručně nebo je přejmenuj na `00.jpg`, `01.jpg`… Se špatným pořadím vyjde jiný
rozbor než ve workeru.

Vypíše řádky po spojení dílů (s vyznačeným řádkem celkové částky), rozebrané položky,
kontrolní součet a varování ve stejném tvaru jako worker. Rozbor dělá tentýž kód, takže
regexy se dají ladit nad uloženým `.ocr.txt` opakovaně a bez GPU. Návratový kód 0 znamená,
že součet sedí a nepadlo žádné varování.

Složka `uctenky/` je v `.gitignore`. Do `testdata/` patří jen anonymizovaná kopie
(viz „Testy" a „Nový řetězec").

**Chyba z Ollamy** (`!! účtenka <ID>: Ollama HTTP 400 …`) nese důvod, který Ollama vrátila.
Podrobnosti jsou v jejím logu: `journalctl -u ollama --since "1 hour ago" | grep -B3 "| 400 |"`.
Obrázek celé účtenky (ruční fotka bez výřezů) zabere sám ~4 050 tokenů. Výchozí kontext
Ollamy je 4 096, proto worker posílá `num_ctx` (`ocr.DEFAULT_NUM_CTX`). Bez něj se obrázek
buď nevejde vůbec („exceeds the available context size"), nebo se přepis potichu usekne.
Velmi dlouhou účtenku v jednom obrázku model navíc umí přečíst dokola (opakuje tytéž
položky, dokud nedojde kontext). Proto je lepší fotit ji po dílech.

## Testy

```bash
python3 -m unittest discover -s tools/uctenky -p 'test_*.py'
```

Stejný příkaz spouští CI (job „Worker účtenek“ v `.github/workflows/ci.yml`) u každého PR.
Testy proto nesmí sahat na Ollamu ani na server, obojí se podvrhuje (`FakeOcr`, `FakeApi`).

Fixtures v `testdata/` jsou **anonymizované**. Reálné účtenky (složka `uctenky/` v kořeni,
mimo git) do repozitáře nepatří.

## Nový řetězec

Do `profiles/` přidej modul se třemi funkcemi (`matches`, `read_header`, `split_body`)
a zaregistruj ho v `profiles/__init__.py`. Skládání řádků je společné. K tomu patří
anonymizovaná fixture v `testdata/` a test. Dlouhou účtenku dej do fixture po dílech
(`<řetězec>-dil1.ocr.txt`, …), aby test pokryl i spojení překryvu.

Čtvrtá funkce `parse_items` je volitelná. Patří jen účtenkám, jejichž položky společné
skládání neumí. Příklad je [`profiles/tamda.py`](profiles/tamda.py): číslované položky
s EAN na druhém řádku a překryvy dílů, které `merge_segments` nepozná. Duplicity se tam
poznají podle pořadového čísla. Druhý příklad je [`profiles/coop.py`](profiles/coop.py).
COOP tiskne množství POD název, kdežto společné skládání (Penny) ho čeká NAD názvem.
Bez vlastního rozboru by se množství přilepilo k sousední položce a kontrolní součet by
to nepoznal.

**EAN z účtenky** jde na server jako `internalCode` a server podle něj spáruje zboží
(`ReceiptMatchingService.byCode`). Server kontroluje jen formát, proto worker posílá jen
kód s platnou kontrolní číslicí (`normalize.is_valid_gtin`). OCR v čárových kódech běžně
splete jednu číslici, a chybný EAN by spároval jiné zboží.
