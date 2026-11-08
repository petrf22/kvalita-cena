"""Návrh kategorie a jednotky nového zboží lokálním modelem — jen předvyplnění formuláře.

Ke každému řádku účtenky model navrhne kategorii appky (slug z nabídnutého seznamu) a rozměr
gramáže (G/KG/ML/L — v jakých jednotkách se zboží udává na obalu). Číslo gramáže model
nedodává nikdy: to umí jen vytištěné označení (pretty.py, vodítko gramáže).

Výstup projde `accept` — slug musí být ze seznamu, jednotka z povolených, váhové zboží jednotku
nedostane (je za kg/l, gramáž nemá) a jednotka nesmí odporovat vytištěnému vodítku. Co
neprojde, se zahodí. Server totéž ověří znovu a uloží jen do ai.receipt_line_hint; formulář
z toho udělá předvyplnění, které uživatel vidí a potvrzuje (docs/ai.md, „Návrh kategorie
a jednotky", „AI nikdy nerozhoduje").
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

# Qwen2.5 14B Instruct, Apache-2.0 (ověřeno `ollama show` 2026-11-02; varianty 3B a 72B mají
# vlastní licenci Qwen). Na zatřídění do ~100 kategorií je 7B znatelně horší (svíčka → pečivo,
# mikrovlnka → papírové zboží na testovacích účtenkách); 14B se na 8 GB VRAM nevejde celý
# a běží částečně na CPU, ale úloha je dávková a na latenci nikdo nečeká (docs/ai.md).
DEFAULT_MODEL = "qwen2.5:14b-instruct"

UNITS = {"G", "KG", "ML", "L"}
_MASS = {"G", "KG"}

SYSTEM_PROMPT = (
    "Zatřiďuješ zboží z českých pokladních účtenek do kategorií obchodu. Dostaneš JSON "
    "{\"kategorie\": {slug: cesta}, \"polozky\": {id: název}}. Pro KAŽDOU položku vrať pod "
    "stejným id objekt {\"category\": slug, \"unit\": jednotka}. category je PŘESNĚ jeden klíč "
    "ze slovníku kategorie (třeba \"jogurty\"), nikdy cesta ani nový slug — hodnota u klíče je jen "
    "česká cesta pro orientaci. Vyber kategorii, kam by zboží zařadil prodavač v supermarketu, "
    "a to nejkonkrétnější, která sedí "
    "(rohlík -> pečivo, Gran Moravia je sýr, losos je ryba). null jen tehdy, když nejde o zboží "
    "(záloha, taška, sleva) nebo název vůbec nerozumíš. unit je jednotka, ve které se gramáž "
    "tohoto zboží píše na obalu: G nebo KG pro hmotnost, ML nebo L pro objem (nápoje, mléko, "
    "olej); null, když se prodává po kusech bez gramáže. Odpověz jen JSON objektem."
)


def _leaf_slug(category: str) -> str:
    """Poslední segment cesty „rodic/potomek" nebo „Rodič › Potomek" — kandidát na slug."""
    return category.replace("›", "/").rsplit("/", 1)[-1].strip()


def accept(answer, line: dict, slugs: set[str]) -> tuple[str | None, str | None, list[str]]:
    """(kategorie, jednotka, důvody zamítnutí) — co neprojde kontrolou, je None."""
    reasons: list[str] = []
    if not isinstance(answer, dict):
        return None, None, ["odpověď není objekt"]
    # Model vrací platný JSON, ale ne nutně tvar, o který si řekl („unit": ["L"], objekt…) —
    # cokoli jiného než řetězec se zahodí, nesmí to shodit celou účtenku (seznam není hashovatelný).
    category = answer.get("category")
    if isinstance(category, str) and category not in slugs:
        leaf = _leaf_slug(category)
        if leaf in slugs:
            # Model občas vrátí vymyšlenou cestu („mlecko-vyrobky/jogurty") místo klíče.
            # Slug je v číselníku unikátní, takže poslední segment určuje kategorii jednoznačně;
            # neznámý poslední segment se dál zamítá níž.
            category = leaf
    if category is not None and (not isinstance(category, str) or category not in slugs):
        reasons.append(f"neznámá kategorie „{category}“")
        category = None
    unit = answer.get("unit")
    if unit is not None and not isinstance(unit, str):
        reasons.append(f"jednotka není text: {unit!r}")
        unit = None
    if isinstance(unit, str):
        unit = unit.strip().upper()
    if unit is not None and unit not in UNITS:
        reasons.append(f"neznámá jednotka „{unit}“")
        unit = None
    if unit is not None and line.get("quantityUnit") in ("KG", "L"):
        # Váhové zboží je za kg/l a gramáž nemá — jednotka by formulář zmátla.
        unit = None
    printed = line.get("netContentUom")
    if unit is not None and printed in UNITS and (unit in _MASS) != (printed in _MASS):
        reasons.append(f"jednotka {unit} odporuje vytištěné {printed}")
        unit = None
    return category, unit, reasons


class OllamaClassifier:
    def __init__(self, host: str, model: str = DEFAULT_MODEL, timeout: int = 300) -> None:
        self.host = host.rstrip("/")
        self.model = model
        self.timeout = timeout

    def complete(self, categories: dict[str, str], items: dict[str, str]) -> dict:
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps({"kategorie": categories, "polozky": items},
                                                       ensure_ascii=False)},
            ],
            "format": "json",
            "stream": False,
            "options": {"temperature": 0, "seed": 0, "num_ctx": 8192},
            "keep_alive": "5m",
        }
        request = urllib.request.Request(
            f"{self.host}/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            content = json.loads(response.read().decode("utf-8"))["message"]["content"]
        parsed = json.loads(content)
        if not isinstance(parsed, dict):
            raise ValueError("model nevrátil JSON objekt")
        return {str(key): value for key, value in parsed.items()}


def classify(out_lines: list[dict], categories: list[dict], classifier, log=print,
             dictionary=None, chain: str | None = None) -> None:
    """Doplní k řádkům `categoryHint` a `unitHint` (jedna dávka na účtenku).

    Kategorie ze slovníku provozovatele (slovnik/kategorie.tsv) má přednost před modelem
    — model nezná značky (Míša, Twister) a tipuje podle slov v názvu. Jednotku dál navrhuje
    model. Bez seznamu kategorií se nic nedoplní; výpadek modelu není chyba účtenky.
    """
    if not categories or not out_lines:
        return
    by_slug = {c["slug"]: c.get("path") or c["slug"] for c in categories if c.get("slug")}
    slugs = set(by_slug)
    items = {str(index): line.get("labelDisplay") or line["label"] for index, line in enumerate(out_lines)}
    known = _dictionary_categories(out_lines, items, dictionary, chain, slugs, log)

    answers = None
    if classifier is not None:
        try:
            answers = classifier.complete(by_slug, items)
        except (urllib.error.URLError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
            log(f"     ~ kategorie: model nedostupný ({error}), návrh jen ze slovníku")
    if answers is None:
        for key, slug in known.items():
            out_lines[int(key)].update(categoryHint=slug, unitHint=None)
        return

    for key, name in items.items():
        line = out_lines[int(key)]
        answer = answers.get(key)
        if key in known:
            # Kategorii určil slovník — co si o ní myslel model, je jedno; od modelu se bere
            # jen jednotka (pod stejnou kontrolou).
            answer = {**answer, "category": known[key]} if isinstance(answer, dict) else {"category": known[key]}
        try:
            category, unit, reasons = accept(answer, line, slugs)
        except (TypeError, ValueError, AttributeError) as error:
            # Poradní výstup — nečekaný tvar odpovědi je jen řádek bez návrhu, ne chyba účtenky.
            category, unit, reasons = known.get(key), None, [f"nečekaný tvar odpovědi ({error})"]
        for reason in reasons:
            log(f"     ~ kategorie „{name}“: model zamítnut — {reason}")
        line["categoryHint"] = category
        line["unitHint"] = unit


def _dictionary_categories(out_lines: list[dict], items: dict[str, str], dictionary, chain: str | None,
                           slugs: set[str], log) -> dict[str, str]:
    """{id řádku: slug} ze slovníku; hledá se v čitelném názvu i v syrovém tisku."""
    if dictionary is None:
        return {}
    known: dict[str, str] = {}
    for key, name in items.items():
        line = out_lines[int(key)]
        slug = dictionary.category(name, chain) or dictionary.category(line["label"], chain)
        if slug is None:
            continue
        if slug not in slugs:
            log(f"     ~ kategorie „{name}“: slovník míří na neznámý slug „{slug}“, ignoruje se")
            continue
        known[key] = slug
    return known
