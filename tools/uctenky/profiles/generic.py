"""Obecný profil pro řetězce, které zatím vlastní profil nemají.

Účtenky českých obchodů mají společnou kostru: nahoře název a adresa provozovny s IČ/DIČ,
uprostřed položky, pak řádek s celkovou částkou („Celkem", „K úhradě", „Součet") a pod ním
patička s platbou. Obecný profil se opírá jen o tuhle kostru — skládání řádků je stejné jako
u vlastních profilů (lines.py). Když něco nesedí, pozná se to na kontrolním součtu
a uživatel řádky stejně potvrzuje jeden po druhém.

Z patičky se stejně jako u vlastních profilů bere JEN datum, čas a celková částka
(docs/soukromi.md, „Účtenky") — číslo karty ani věrnostní údaje dál nejdou. Jedinou výjimkou
je adresa prodejny v profilu coop.
"""

from __future__ import annotations

import re
from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

from normalize import AMOUNT, AMOUNT_RE, VAT_PREFIX_COUNTRY, is_valid_ico, parse_amount
from profiles.base import Footer, Profile, ReceiptHeader

COUNTRY = "CZ"
CURRENCY = "CZK"
TIMEZONE = ZoneInfo("Europe/Prague")
HEADER_LINES = 10

_POSTAL_CITY_RE = re.compile(r"^(?P<postal>\d{3}\s?\d{2})\s+(?P<city>\D.*)$")
_ICO_RE = re.compile(r"\b(?:IČO?|ICO?)\s*[:.]?\s*(?P<ico>\d{8})\b", re.IGNORECASE)
# IČO pod popiskem, který OCR zkomolilo: „ICU", „IC0", „JČO", „1ČO". Bere se jen s platnou
# kontrolní číslicí (normalize.is_valid_ico) a nesmí to být konec slova („DIČ: 12345678").
_ICO_OCR_RE = re.compile(r"(?<![^\W\d_])[IJl1|][ČčCc][OoUuQ0D]?\s*[:.]?\s*(?P<ico>\d{8})\b")
# DIČ a jeho obdoby v dalších jazycích: VAT No./Number/ID, USt-IdNr. (DE), UID (AT), IČ DPH
# (SK), NIP (PL). Prefix před číslem je kód státu (normalize.VAT_PREFIX_COUNTRY). U české
# právnické osoby je DIČ = „CZ" + IČO; u fyzické osoby je za CZ rodné číslo (9-10 číslic),
# a to se nikam dál neposílá (docs/soukromi.md).
_VAT_ID_RE = re.compile(
    r"\b(?P<label>DI[ČC]|I[ČC]\s*DPH|VAT(?:\s*(?:Reg\.?\s*)?(?:No\.?|Number|ID))?"
    r"|USt\.?\s*-?\s*Id\.?\s*-?\s*Nr\.?|UID(?:\s*-?\s*Nr\.?)?|NIP)"
    r"\s*[:.]?\s*(?P<prefix>[A-Z]{2})?\s*(?P<number>U?\d[\dA-Z]{1,13})\b",
    re.IGNORECASE,
)
_COMPANY_LINE_RE = re.compile(
    r"\b(IČO?|DIČ|ICO?|s\.\s?r\.\s?o\.|a\.\s?s\.|spol\.|soud|spis\.?\s*zn|zaps[aá]n|datum|čas|cas\s*:|"
    r"pokladn|provozovna|filiálka|tel\.?|www\.)",
    re.IGNORECASE,
)
_TOTAL_RE = re.compile(
    rf"^(?:celkem(?:\s+k\s+úhradě)?|k\s+úhradě|k\s+zaplacení|součet|suma|total)\b\D*"
    rf"(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$",
    re.IGNORECASE,
)
# Mezisoučet a zaokrouhlení NAD celkovou částkou (Penny) — nejsou to položky, blok položek
# jimi končí. Zaokrouhlení se ale do kontrolního součtu připočte (Footer.rounding).
_SUBTOTAL_RE = re.compile(r"^(?:mezisou[čc]et|zaokrouhlen[ií])\b", re.IGNORECASE)
_ROUNDING_RE = re.compile(rf"^zaokrouhlen[ií]\b\D*?(?P<amount>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)
# Datum a čas na dvou řádcích (Penny): „Datum: 20.09.2026" a pod ním „Čas: 08:22:39".
_DATE_ONLY_RE = re.compile(r"\b(?P<day>\d{1,2})[./](?P<month>\d{1,2})[./](?P<year>\d{4}|\d{2})\b")
_TIME_ONLY_RE = re.compile(r"^(?:[čc]as\b\s*:?\s*)?(?P<hour>\d{1,2}):(?P<minute>\d{2})(?::\d{2})?\b", re.IGNORECASE)
# 30.09.2026 18:42(:05) | 30.09.26 18:42 | 30/09/26 18:42 | 2026-09-30 18:42
_DATETIME_RES = (
    re.compile(r"\b(?P<day>\d{1,2})[./](?P<month>\d{1,2})[./](?P<year>\d{2}|\d{4})\s+(?P<hour>\d{1,2}):(?P<minute>\d{2})"),
    re.compile(r"\b(?P<year>\d{4})-(?P<month>\d{2})-(?P<day>\d{2})[ T](?P<hour>\d{2}):(?P<minute>\d{2})"),
)


def matches(raw_lines: list[str]) -> bool:
    return True


def _header_end(raw_lines: list[str]) -> int:
    """Index prvního řádku bloku položek: za posledním řádkem adresy/IČ v hlavičce."""
    end = 0
    for index, line in enumerate(raw_lines[:HEADER_LINES]):
        if AMOUNT_RE.search(line) and not _is_company_line(line):
            break
        if _POSTAL_CITY_RE.match(line) or _is_company_line(line):
            end = index + 1
    return end


def _is_company_line(line: str) -> bool:
    return bool(_COMPANY_LINE_RE.search(line) or _ICO_OCR_RE.search(line) or _VAT_ID_RE.search(line))


def _vat_ids(raw_lines: list[str]) -> list[tuple[str, str | None, str]]:
    """(popisek, prefix státu velkými písmeny nebo None, číslo) z hlavičky."""
    found = []
    for line in raw_lines[:HEADER_LINES + 5]:
        for match in _VAT_ID_RE.finditer(line):
            prefix = match["prefix"].upper() if match["prefix"] else None
            found.append((match["label"], prefix, match["number"]))
    return found


def find_ico(raw_lines: list[str]) -> str | None:
    """IČO z hlavičky: popisek IČO, pak IČO pod zkomoleným popiskem, nakonec české DIČ."""
    lines = raw_lines[:HEADER_LINES + 5]
    for line in lines:
        match = _ICO_RE.search(line)
        if match:
            return match["ico"]
    for line in lines:
        for match in _ICO_OCR_RE.finditer(line):
            if is_valid_ico(match["ico"]):
                return match["ico"]
    for label, prefix, number in _vat_ids(raw_lines):
        # Bez prefixu jen pod českým popiskem DIČ — „NIP: 52602509" IČO není.
        czech = prefix == "CZ" or (prefix is None and label.upper().startswith("DI"))
        if czech and len(number) == 8 and number.isdigit():
            return number
    return None


def find_vat_country(raw_lines: list[str]) -> str | None:
    """Stát provozovatele podle prefixu DIČ / VAT ID („CZ64945880" -> CZ)."""
    for _, prefix, _ in _vat_ids(raw_lines):
        if prefix in VAT_PREFIX_COUNTRY:
            return VAT_PREFIX_COUNTRY[prefix]
    return None


# Řádky hlavičky, které nejsou název obchodu, ani když je horní okraj účtenky useknutý
# a název chybí: identifikátory, zápis v obchodním rejstříku („MĚST. SOUD PRAHA C/42812"),
# kontakt a údaje pokladny. „s.r.o." sem nepatří — to v názvu je.
_NOT_NAME_RE = re.compile(
    r"\b(?:tel\.?|www\.|datum|čas|cas\s*:|pokladn|e-?mail|soud|spis\.?\s*zn|zaps[aá]n|rejst[řr]ík)"
    r"|\b[A-Z]{1,2}\s*/\s*\d{3,}\b",
    re.IGNORECASE,
)


# Ulice s číslem domu na konci („Hlavní 12", „Nádražní 1520/3a").
_STREET_RE = re.compile(r"\D\s\d+[a-zA-Z]?(?:/\d+[a-zA-Z]?)?$")


def store_name(header: list[str]) -> str | None:
    """První řádek hlavičky, který je název — ne IČO/DIČ, adresa ani kontakt.

    Když je horní okraj účtenky useknutý, název chybí úplně; pak je lepší žádný název než
    „ICU: 64945880" nebo ulice — obchod se dál dá najít podle IČO a adresy.
    """
    for index, line in enumerate(header):
        if (_ICO_RE.search(line) or _ICO_OCR_RE.search(line) or _VAT_ID_RE.search(line)
                or _POSTAL_CITY_RE.match(line) or _NOT_NAME_RE.search(line)):
            continue
        before_city = index + 1 < len(header) and _POSTAL_CITY_RE.match(header[index + 1])
        if before_city and _STREET_RE.search(line):
            continue
        return line
    return None


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    end = _header_end(raw_lines)
    header = raw_lines[:end] if end else raw_lines[:1]
    raw_name = store_name(header)
    street, postal, city = None, None, None
    for index, line in enumerate(header):
        match = _POSTAL_CITY_RE.match(line)
        if match:
            postal, city = match["postal"], match["city"].strip()
            if index > 0 and not _is_company_line(header[index - 1]) and header[index - 1] != raw_name:
                street = header[index - 1]
            break
    return ReceiptHeader(raw_name=raw_name, country=COUNTRY, street=street, postal_code=postal,
                         city=city, ico=find_ico(raw_lines))


def parse_datetime(line: str) -> datetime | None:
    for pattern in _DATETIME_RES:
        match = pattern.search(line)
        if not match:
            continue
        year = int(match["year"])
        year = 2000 + year if year < 100 else year
        try:
            return datetime(year, int(match["month"]), int(match["day"]),
                            int(match["hour"]), int(match["minute"]), tzinfo=TIMEZONE)
        except ValueError:
            continue
    return None


def parse_split_datetime(lines: list[str]) -> datetime | None:
    """Datum na jednom řádku a čas hned na dalším („Datum: 20.09.2026" / „Čas: 08:22:39")."""
    for index, line in enumerate(lines[:-1]):
        date = _DATE_ONLY_RE.search(line)
        time = _TIME_ONLY_RE.match(lines[index + 1])
        if not date or not time:
            continue
        year = int(date["year"])
        try:
            return datetime(2000 + year if year < 100 else year, int(date["month"]), int(date["day"]),
                            int(time["hour"]), int(time["minute"]), tzinfo=TIMEZONE)
        except ValueError:
            continue
    return None


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    start = _header_end(raw_lines)
    end, printed_total = len(raw_lines), None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            end, printed_total = index, parse_amount(total["total"])
            break

    body_end, rounding = end, Decimal(0)
    if printed_total is not None:
        for index in range(start, end):
            if _SUBTOTAL_RE.match(raw_lines[index]):
                body_end = min(body_end, index)
                rounded = _ROUNDING_RE.match(raw_lines[index])
                if rounded:
                    rounding += parse_amount(rounded["amount"])

    purchased_at = None
    # Datum bývá v patičce; některé pokladny ho tisknou do hlavičky.
    outside = [*raw_lines[end:], *raw_lines[:start]]
    for line in outside:
        purchased_at = parse_datetime(line)
        if purchased_at:
            break
    if purchased_at is None:
        purchased_at = parse_split_datetime(raw_lines[end:]) or parse_split_datetime(raw_lines[:start])
    return raw_lines[start:body_end], Footer(purchased_at=purchased_at, printed_total=printed_total,
                                             total_index=end if printed_total is not None else None,
                                             rounding=rounding)


PROFILE = Profile(
    name="obecny",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
)
