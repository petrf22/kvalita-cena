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

export KC_API_URL=https://kvalitacena.cz        # lokálně http://localhost:8080
export KC_WORKER_TOKEN=kcw_...                  # viz „Token" níž
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

Když worker neběží, účtenky jen čekají ve frontě a appka funguje beze změny. Worker, který
spadne uprostřed práce, nevadí: lease po 15 minutách vyprší a účtenka se vrátí do fronty.

### Token

Worker se přihlašuje strojovým tokenem, ne účtem uživatele. V databázi je uložený jen jeho
SHA-256:

```bash
TOKEN="kcw_$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
echo "$TOKEN"   # ulož si ho; znovu už ho nezjistíš
docker compose exec postgres psql -U postgres -d kvalitaacena -c \
  "INSERT INTO auth.api_token(name, token_hash, scope)
   VALUES ('domaci-pc', sha256(convert_to('$TOKEN', 'UTF8')), 'RECEIPT_WORKER');"
```

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

## Dvě věci, které vypadají jako detail, a nejsou

**Strukturu nesmí tahat jazykový model.** Ověřili jsme to na reálné účtence Penny: druhý
průchod přes `qwen2.5:14b-instruct` zahodil řádek `3.000 ks x 36.90 Kč /ks`. Zůstalo jen
zaplacených 110,70, tedy trojnásobek ceny z regálu. Jindy tentýž model přejmenoval zboží,
a to kontrolní součet nezachytí. Proto model dělá jen OCR (podrobně
[`normalize.py`](normalize.py)).

**U váhového zboží je cena za kg/l, nikdy zaplacená částka.** Z řádku
`0,302 kg x 289,00 Kč/kg 87,30` jde na server cena 289,00 za kg.

## Soukromí

Z patičky účtenky parser vezme jen datum, čas a celkovou částku. Číslo karty, autorizační
kód, čísla pokladny ani věrnostní údaje se na server nevrátí. Hlídají to testy.

Obrázky na serveru navíc uživatel před odesláním maskuje na zařízení.

## Testy

```bash
python3 -m unittest discover -s tools/uctenky -p 'test_*.py'
```

Fixtures v `testdata/` jsou **anonymizované**. Reálné účtenky (složka `uctenky/` v kořeni,
mimo git) do repozitáře nepatří.

## Nový řetězec

Do `profiles/` přidej modul se třemi funkcemi (`matches`, `read_header`, `split_body`)
a zaregistruj ho v `profiles/__init__.py`. Skládání řádků je společné. K tomu patří
anonymizovaná fixture v `testdata/` a test.
