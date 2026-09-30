"""Profil účtenky Albert (CZ).

Tvar účtenky:

    albert                       <- název řetězce
    B. Němcové 960               <- ulice
    337 01 Rokycany              <- PSČ a město
    ...blok položek...
    Celkem 986,20                <- konec bloku položek
    18/08/26 16:09 ...           <- z patičky se bere JEN datum a čas

Vše ostatní z patičky (číslo karty, SEQ ID, autorizační kód, čísla pokladny a obsluhy,
věrnostní body, čárový kód účtenky) se zahazuje — na server se nikdy nedostane.
"""

from __future__ import annotations

import re
from datetime import datetime
from zoneinfo import ZoneInfo

from normalize import AMOUNT, parse_amount
from profiles.base import Footer, Profile, ReceiptHeader

CHAIN_SLUG = "albert"
COUNTRY = "CZ"
CURRENCY = "CZK"
TIMEZONE = ZoneInfo("Europe/Prague")

_NAME_RE = re.compile(r"albert", re.IGNORECASE)
_POSTAL_CITY_RE = re.compile(r"^(?P<postal>\d{3}\s?\d{2})\s+(?P<city>\D.*)$")
_TOTAL_RE = re.compile(rf"^Celkem\b\D*(?P<total>{AMOUNT})\s*(?:Kč|Kc|CZK)?\s*$", re.IGNORECASE)
_DATETIME_RE = re.compile(
    r"\b(?P<day>\d{2})[/.](?P<month>\d{2})[/.](?P<year>\d{2})\s+(?P<hour>\d{2}):(?P<minute>\d{2})\b"
)


def matches(raw_lines: list[str]) -> bool:
    """Název řetězce stojí v hlavičce, ale OCR ho občas rozhodí — hledá se v prvních řádcích."""
    return any(_NAME_RE.search(line) for line in raw_lines[:5])


def read_header(raw_lines: list[str]) -> ReceiptHeader:
    raw_name, street, postal, city = None, None, None, None
    for line in raw_lines[:6]:
        if raw_name is None and _NAME_RE.search(line):
            raw_name = line
            continue
        if raw_name is None:
            continue
        postal_city = _POSTAL_CITY_RE.match(line)
        if postal_city:
            postal = postal_city["postal"]
            city = postal_city["city"].strip()
            break
        if street is None:
            street = line
    return ReceiptHeader(
        raw_name=raw_name,
        chain_slug=CHAIN_SLUG,
        country=COUNTRY,
        street=street,
        postal_code=postal,
        city=city,
    )


def split_body(raw_lines: list[str]) -> tuple[list[str], Footer]:
    """Rozdělí účtenku na blok položek a patičku; z patičky vrátí jen datum, čas a součet."""
    start = 0
    for index, line in enumerate(raw_lines[:6]):
        if _POSTAL_CITY_RE.match(line):
            start = index + 1
            break

    end, printed_total = len(raw_lines), None
    for index in range(start, len(raw_lines)):
        total = _TOTAL_RE.match(raw_lines[index])
        if total:
            end, printed_total = index, parse_amount(total["total"])
            break

    purchased_at = None
    for line in raw_lines[end:]:
        stamp = _DATETIME_RE.search(line)
        if stamp:
            purchased_at = datetime(
                2000 + int(stamp["year"]), int(stamp["month"]), int(stamp["day"]),
                int(stamp["hour"]), int(stamp["minute"]), tzinfo=TIMEZONE,
            )
            break

    return raw_lines[start:end], Footer(purchased_at=purchased_at, printed_total=printed_total)


PROFILE = Profile(
    name="albert",
    currency=CURRENCY,
    matches=matches,
    read_header=read_header,
    split_body=split_body,
)
