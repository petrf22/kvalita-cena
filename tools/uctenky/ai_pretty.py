"""Dočištění čitelného názvu lokálním modelem — JEN kosmetika pod deterministickou kontrolou.

Model dostane názvy, které pravidla a slovník (pretty.py) nedořešily, a smí v nich doplnit
diakritiku, opravit velikost písmen a rozepsat zkratku s tečkou. Nic víc: dřívější pokus
nechat model „učesat" celou účtenku tiše zahodil řádek s množstvím a přejmenoval zboží
(normalize.py). Proto každý výstup projde `accept` — stejný počet slov, čísla a jednotky
beze změny, každé slovo bez diakritiky shodné s původním, nebo rozepsaná zkratka. Co
neprojde, se zahodí a platí verze z pravidel.

Výsledek je jen návrh pro oči a hledání (`receipt_line.label_display`), nikdy nemění
syrové označení, podle kterého se učí mapování na zboží (docs/ai.md, „AI nikdy nerozhoduje").
"""

from __future__ import annotations

import json
import re
import unicodedata
import urllib.error
import urllib.request
from dataclasses import replace

import pretty

# Qwen2.5 7B Instruct je pod Apache-2.0 (pozor: varianty 3B a 72B mají vlastní licenci Qwen)
# — v souladu s pravidlem „pouze svobodné licence" (docs/ai.md, „Hardware a volba modelu").
DEFAULT_MODEL = "qwen2.5:7b-instruct"

SYSTEM_PROMPT = (
    "Jsi korektor názvů zboží z českých pokladních účtenek. Dostaneš JSON objekt "
    "{id: název}. Vrať JSON objekt se STEJNÝMI klíči a opraveným názvem. Smíš jen: doplnit "
    "chybějící českou diakritiku (ARASIDOVA -> arašídová), opravit velikost písmen (první slovo "
    "velkým, značky jako Olma velkým, ostatní malými) a rozepsat zkratku končící tečkou na celé "
    "slovo (SELS. -> Selské), ale jen když si jsi jistý — jinak ji nech, jak je. Nesmíš přidat "
    "ani ubrat slovo, změnit pořadí slov, přeložit slovo ani změnit číslo nebo jednotku "
    "(43g, 0,5l, 3,9% zůstávají přesně). Odpověz jen JSON objektem."
)

_NUMBER_TOKEN_RE = re.compile(r"\d")


def _fold(token: str) -> str:
    return pretty.unaccent(token).lower()


def _same_letters(mine: str, other: str) -> bool:
    """Písmena sedí, model smí jen PŘIDAT diakritiku písmenu, které ji nemá.

    Diakritiku, kterou pokladna vytiskla, model odebrat ani změnit nesmí — „ÖLZ" (značka)
    by jinak skončil jako „Olz". Porovnává se bez ohledu na velikost písmen.
    """
    mine = unicodedata.normalize("NFC", mine.lower())
    other = unicodedata.normalize("NFC", other.lower())
    if len(mine) != len(other):
        return False
    for ours, theirs in zip(mine, other):
        if ours == theirs:
            continue
        if not ours.isascii() or _fold(theirs) != ours:
            return False
    return True


def accept(rules_text: str, ai_text: str) -> str | None:
    """None = výstup modelu je v pořádku; jinak důvod zamítnutí (do logu workeru)."""
    if not isinstance(ai_text, str) or not ai_text.strip():
        return "prázdný výstup"
    ours = rules_text.split()
    theirs = ai_text.split()
    if len(ours) != len(theirs):
        return f"jiný počet slov ({len(ours)} → {len(theirs)})"
    for mine, other in zip(ours, theirs):
        if _NUMBER_TOKEN_RE.search(mine) or _NUMBER_TOKEN_RE.search(other):
            # Čísla a jednotky musí sedět přesně (velikost písmen jednotky taky — „1L" vs „1l"
            # je u nás vždycky malými).
            if mine != other:
                return f"změněné číslo „{mine}“ → „{other}“"
            continue
        if _same_letters(mine, other):
            continue
        if mine.endswith(".") and len(mine) > 2 and not other.endswith(".") and other.isalpha():
            stem = mine[:-1]
            if len(other) > len(stem) and _same_letters(stem, other[:len(stem)]):
                continue
        return f"změněné slovo „{mine}“ → „{other}“"
    return None


class OllamaPrettifier:
    def __init__(self, host: str, model: str = DEFAULT_MODEL, timeout: int = 300) -> None:
        self.host = host.rstrip("/")
        self.model = model
        self.timeout = timeout

    def complete(self, names: dict[str, str]) -> dict[str, str]:
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(names, ensure_ascii=False)},
            ],
            "format": "json",
            "stream": False,
            "options": {"temperature": 0, "seed": 0},
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


def polish(items: list[pretty.Pretty], prettifier, log=print) -> list[pretty.Pretty]:
    """Pošle modelu jen nedořešené názvy (jedna dávka na účtenku) a přijme, co projde kontrolou.

    Výpadek modelu není chyba účtenky — názvy zůstanou z pravidel (docs/ai.md, degradace).
    """
    if prettifier is None:
        return items
    pending = {str(index): item.text for index, item in enumerate(items)
               if item.source == pretty.SOURCE_RULES and item.unresolved}
    if not pending:
        return items
    try:
        answers = prettifier.complete(pending)
    except (urllib.error.URLError, OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        log(f"     ~ čitelné názvy: model nedostupný ({error}), zůstávají názvy z pravidel")
        return items
    result = list(items)
    for key, rules_text in pending.items():
        answer = answers.get(key)
        if answer is None or answer == rules_text:
            continue
        reason = accept(rules_text, answer)
        if reason:
            # I zamítnutý výstup se vypíše — podle něj jde rovnou doplnit slovnik/*.tsv.
            log(f"     ~ čitelný název „{rules_text}“: model zamítnut — {reason}: „{answer}“")
            continue
        index = int(key)
        result[index] = replace(items[index], text=pretty._capitalize(answer.strip()), source=pretty.SOURCE_AI)
    return result
