"""Společný tvar profilu řetězce."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Callable


@dataclass(frozen=True)
class ReceiptHeader:
    """Údaje z hlavičky, podle kterých se hledá provozovna v katalogu."""

    raw_name: str | None = None
    chain_slug: str | None = None
    country: str | None = None
    street: str | None = None
    postal_code: str | None = None
    city: str | None = None


@dataclass(frozen=True)
class Footer:
    """Jediné, co se z patičky bere. Zbytek se zahazuje — viz docs/soukromi.md."""

    purchased_at: datetime | None = None
    printed_total: Decimal | None = None


@dataclass(frozen=True)
class Profile:
    name: str
    currency: str
    matches: Callable[[list[str]], bool]
    read_header: Callable[[list[str]], ReceiptHeader]
    # Vrátí (řádky bloku položek, patička).
    split_body: Callable[[list[str]], tuple[list[str], Footer]]
