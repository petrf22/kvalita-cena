"""Očista syrového OCR textu a práce s částkami (kroky 1-2 normalizace).

Záměrně obsahuje JEN deterministická pravidla (regexy). „Vylepšení" textu jazykovým
modelem se neosvědčilo — druhý průchod přes qwen2.5:14b-instruct nad výstupem OCR
z účtenky Penny (06.09.2026) zahodil celý řádek s množstvím:

    3.000 ks x 36.90 Kč /ks              ->   Balsýr bloček 47% 200g   110.70 Kč
    Balsýr bloček 47% 200g C 110.70

Zůstalo 110,70, což je zaplaceno za TŘI kusy; cena z regálu je 36,90/ks. Importér
krmený takovým textem uloží trojnásobek. Týž model při jiném běhu přejmenoval zboží
(GRAN MORAV.STR.100G -> „GRAN MAROKSKÝ STR."), což je horší — tichá halucinace, kterou
kontrolní součet nezachytí. Model proto dělá výhradně obrázek -> text, nic dál.
"""

from __future__ import annotations

import re
import unicodedata
from decimal import Decimal

# Nula se z OCR často vrací jako písmeno O — opravuje se VÝHRADNĚ uvnitř tokenu, který má
# jinak tvar částky. Plošná záměna přes celý řádek by mrzačila názvy zboží.
_D = "[0-9Oo]"

# 986,20 | 1 369,20 | -79,00 | 986.20 (mezera i nedělitelná mezera jako oddělovač tisíců)
AMOUNT = rf"-?{_D}{{1,3}}(?:[  ]{_D}{{3}})*[.,]{_D}{{2}}"
AMOUNT_RE = re.compile(AMOUNT)

_ZERO_LIKE = str.maketrans({"O": "0", "o": "0"})


def parse_amount(text: str) -> Decimal:
    """Částka z účtenky na Decimal. Desetinná čárka, mezera v tisících, O místo nuly."""
    cleaned = text.translate(_ZERO_LIKE).replace(" ", "").replace(" ", "").replace(",", ".")
    return Decimal(cleaned)


def format_amount(value: Decimal) -> str:
    """Peněžní hodnota do JSONu jako řetězec — přes float by se cestou rozbila."""
    return f"{value:.2f}"


def clean_line(line: str) -> str:
    """Sjednotí mezery a znaky, které OCR střídá (× vs x, nedělitelné mezery, uvozovky)."""
    text = unicodedata.normalize("NFC", line)
    text = text.replace(" ", " ").replace(" ", " ").replace(" ", " ")
    text = text.replace("×", "x").replace("✕", "x")
    text = re.sub(r"\s+", " ", text)
    return text.strip()


# Oddělovací linky z tisku (---- . . . . ====) nenesou žádný údaj.
_SEPARATOR_RE = re.compile(r"^[\s\-_=.·•~]*$")
# Hlavička cenového sloupce.
_COLUMN_HEADER_RE = re.compile(r"^(Kč|KČ|Kc|CZK|EUR|€)$", re.IGNORECASE)


def is_noise(line: str) -> bool:
    """Řádek bez užitečného obsahu — oddělovač nebo hlavička sloupce."""
    return bool(_SEPARATOR_RE.match(line)) or bool(_COLUMN_HEADER_RE.match(line))


def clean_lines(text: str) -> list[str]:
    """Syrový OCR text na očištěné řádky bez prázdných a bez oddělovačů."""
    return [c for line in text.splitlines() if (c := clean_line(line)) and not is_noise(c)]
