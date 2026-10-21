"""Registr profilů řetězců.

Každý řetězec tiskne účtenku jinak, ale rozdíl je jen v hlavičce, patičce a vymezení
bloku položek — samotné skládání řádků je společné (lines.py). Profil proto dodává
jen detekci a tři metody, ne vlastní parser.
"""

from __future__ import annotations

from profiles import albert, generic
from profiles.base import Profile, ReceiptHeader

_PROFILES: tuple[Profile, ...] = (albert.PROFILE,)


def detect(raw_lines: list[str]) -> Profile:
    """Vrátí první profil, který se hlásí k hlavičce účtenky, jinak obecný profil."""
    for profile in _PROFILES:
        if profile.matches(raw_lines):
            return profile
    return generic.PROFILE


def by_name(name: str) -> Profile | None:
    for profile in (*_PROFILES, generic.PROFILE):
        if profile.name == name:
            return profile
    return None


def names() -> list[str]:
    return [profile.name for profile in _PROFILES]


__all__ = ["Profile", "ReceiptHeader", "detect", "by_name", "names"]
