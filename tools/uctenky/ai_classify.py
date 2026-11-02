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
    "stejným id objekt {\"category\": slug, \"unit\": jednotka}. category je slug z nabídnutých "
    "kategorií, kam by zboží zařadil prodavač v supermarketu — vyber nejkonkrétnější, která sedí "
    "(rohlík -> pečivo, Gran Moravia je sýr, losos je ryba). null jen tehdy, když nejde o zboží "
    "(záloha, taška, sleva) nebo název vůbec nerozumíš. unit je jednotka, ve které se gramáž "
    "tohoto zboží píše na obalu: G nebo KG pro hmotnost, ML nebo L pro objem (nápoje, mléko, "
    "olej); null, když se prodává po kusech bez gramáže. Odpověz jen JSON objektem."
)


def accept(answer, line: dict, slugs: set[str]) -> tuple[str | None, str | None, list[str]]:
    """(kategorie, jednotka, důvody zamítnutí) — co neprojde kontrolou, je None."""
    reasons: list[str] = []
    if not isinstance(answer, dict):
        return None, None, ["odpověď není objekt"]
    category = answer.get("category")
    if category is not None and (not isinstance(category, str) or category not in slugs):
        reasons.append(f"neznámá kategorie „{category}“")
        category = None
    unit = answer.get("unit")
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


def classify(out_lines: list[dict], categories: list[dict], classifier, log=print) -> None:
    """Doplní k řádkům `categoryHint` a `unitHint` (jedna dávka na účtenku).

    Bez modelu nebo bez seznamu kategorií se nic nedoplní; výpadek modelu není chyba účtenky.
    """
    if classifier is None or not categories or not out_lines:
        return
    by_slug = {c["slug"]: c.get("path") or c["slug"] for c in categories if c.get("slug")}
    items = {str(index): line.get("labelDisplay") or line["label"] for index, line in enumerate(out_lines)}
    try:
        answers = classifier.complete(by_slug, items)
    except (urllib.error.URLError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        log(f"     ~ kategorie: model nedostupný ({error}), formulář zůstane bez návrhu")
        return
    slugs = set(by_slug)
    for key, name in items.items():
        line = out_lines[int(key)]
        category, unit, reasons = accept(answers.get(key), line, slugs)
        for reason in reasons:
            log(f"     ~ kategorie „{name}“: model zamítnut — {reason}")
        line["categoryHint"] = category
        line["unitHint"] = unit
