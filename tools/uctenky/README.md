# Import účtenek — lokální nástroj

Řetěz **obrázek → text → normalizovaný JSON → server**. Běží u tebe na PC proti lokální
Ollamě, na serveru končí jako návrh cen k potvrzení. Rozhodnutí kolem téhle funkce jsou
v [`docs/rozvoj.md`](../../docs/rozvoj.md) („Načtení celé účtenky", „Mapování obchodního
označení zboží na katalogovou položku") a [`docs/ai.md`](../../docs/ai.md).

Jen standardní knihovna Pythonu 3.12 — žádné `pip install`.

## Postup

```bash
# 1) OCR (sekvenčně, hotové soubory přeskakuje; ~8 s na účtenku na RTX 4060 Ti)
python3 tools/uctenky/ocr.py --dir uctenky

# 2) Normalizace do receipt-v1 + kontrola součtu
python3 tools/uctenky/parse.py --dir uctenky --report

# 3) Nahrání na server (účtenky, jejichž součet nesedí, se přeskočí)
KAC_TOKEN=... python3 tools/uctenky/upload.py uctenky/*.receipt.json
```

Ke každému `<jmeno>.png` vzniknou `<jmeno>.ocr.txt`, `<jmeno>.ocr.json`
a `<jmeno>.receipt.json`. Adresář `uctenky/` je mimo git (`.git/info/exclude`) — skeny
ani jejich přepisy do repozitáře nepatří.

## Dvě věci, které vypadají jako detail, a nejsou

**Normalizace nesmí jít přes jazykový model.** Druhý průchod přes
`qwen2.5:14b-instruct` nad výstupem OCR z účtenky Penny (06.09.2026) zahodil celý řádek
s množstvím:

```
3.000 ks x 36.90 Kč /ks           ->   Balsýr bloček 47% 200g        110.70 Kč
Balsýr bloček 47% 200g C 110.70
```

Zůstalo 110,70 — zaplaceno za **tři** kusy; cena z regálu je 36,90/ks. Importér krmený
takovým textem uloží trojnásobek. Týž model při jiném běhu přejmenoval zboží
(`GRAN MORAV.STR.100G` → „GRAN MAROKSKÝ STR."), což je horší: tichá halucinace, kterou
kontrolní součet nezachytí, protože částky sedí. Model proto dělá **výhradně obrázek →
text**; text na strukturu převádějí regexy v [`normalize.py`](normalize.py)
a [`lines.py`](lines.py).

**Kontrolní součet je brána kvality, ne ozdoba.** Σ(položky + slevy) musí sedět na
vytištěné „Celkem". Na reálných účtenkách to vychází na haléř, takže rozházené OCR se
pozná spolehlivě — `parse.py` skončí nenulovým návratovým kódem a `upload.py` takovou
účtenku sám neodešle.

## Struktura

| Soubor | Co dělá |
|---|---|
| `ocr.py` | dávkové OCR přes Ollamu (`/api/generate`, `temperature 0`, pevný seed) |
| `normalize.py` | očista textu a částek (kroky 1–2) |
| `lines.py` | klasifikace a skládání řádků, odvození jednotkové ceny (kroky 3–5) |
| `profiles/` | co je specifické pro řetězec: detekce, hlavička, vymezení bloku položek |
| `parse.py` | kontrolní součet a sestavení dokumentu `receipt-v1` (krok 6) + CLI |
| `upload.py` | `POST /api/receipts` |
| `schema/receipt-v1.json` | kontrakt formátu, sdílený s backendem |
| `testdata/` | **anonymizované** fixtures + zlaté kopie výstupu |

Testy: `python3 -m unittest discover -s tools/uctenky`

## Soukromí

Účtenka nese víc než ceny: adresa provozovny a přesný čas nákupu prakticky identifikují
zákazníka, k tomu poslední čtyřčíslí karty a číslo věrnostní karty. **Patičku proto
zahazuje už parser** — do `receipt-v1` se z ní dostane jen datum, čas a celková částka,
nic jiného. Hlídá to `PrivacyTest` v `test_parse.py`. Podrobně `docs/soukromi.md`,
oddíl „Účtenka".

## Nový řetězec

Přidat modul do `profiles/` se třemi funkcemi (`matches`, `read_header`, `split_body`)
a zaregistrovat ho v `profiles/__init__.py`. Skládání řádků je společné, profil řeší jen
hlavičku, patičku a vymezení bloku položek. K tomu anonymizovaná fixture v `testdata/`
a zlatá kopie výstupu.

## Model

Výchozí je `glm-ocr:bf16` ([zai-org/GLM-OCR](https://huggingface.co/zai-org/GLM-OCR),
licence **MIT**) — vyhovuje pravidlu projektu „pouze svobodné licence" (`CLAUDE.md`).
Jiný model přes `--model`; před nasazením ověř jeho licenci, řada modelů po ruce
(Gemma, Llama) pravidlo nesplňuje.
