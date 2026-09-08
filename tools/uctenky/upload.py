#!/usr/bin/env python3
"""Nahrání normalizované účtenky na server.

    python3 tools/uctenky/upload.py uctenky/*.receipt.json --token "$JWT"

Import je idempotentní — server účtenku pozná podle otisku dokumentu a opakované
nahrání vrátí tutéž účtenku místo druhé kopie.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE_URL = "http://localhost:8080"


def upload(document: dict, base_url: str, token: str, timeout: int = 60) -> dict:
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/receipts",
        data=json.dumps(document, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {token}",
            # X-Client-Kind se záměrně neposílá: zdroj cen z účtenky je ObservationSource.IMPORT
            # a určuje ho server podle cesty zápisu, ne klient hlavičkou
            # (docs/rozvoj.md, "Zdroj ceny: kanál klienta vs. druh důkazu").
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Nahrání účtenky receipt-v1 na server")
    parser.add_argument("files", nargs="+", type=Path, help="*.receipt.json soubory")
    parser.add_argument("--base-url", default=os.environ.get("KAC_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument(
        "--token",
        default=os.environ.get("KAC_TOKEN"),
        help="access token (JWT); jde vzít i z proměnné KAC_TOKEN",
    )
    parser.add_argument(
        "--force", action="store_true", help="nahraje i účtenku, jejíž součet nesedí"
    )
    args = parser.parse_args()

    if not args.token:
        parser.error("chybí --token (nebo proměnná KAC_TOKEN)")

    failed = 0
    for source in sorted(args.files):
        document = json.loads(source.read_text(encoding="utf-8"))
        if not document.get("totalMatches") and not args.force:
            print(f"-- {source.name}: součet nesedí, přeskakuji (--force ho nahraje)")
            continue
        try:
            result = upload(document, args.base_url, args.token)
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", errors="replace")[:500]
            print(f"!! {source.name}: HTTP {error.code} {body}", file=sys.stderr)
            failed += 1
            continue
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            print(f"!! {source.name}: {error}", file=sys.stderr)
            failed += 1
            continue
        print(
            f"OK {source.name}: účtenka {result.get('receiptId')},"
            f" {result.get('lineCount')} řádků,"
            f" spárováno {result.get('matchedCount')},"
            f" návrhů {result.get('suggestedCount')}"
            + (" (už byla nahraná)" if result.get("alreadyImported") else "")
        )
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
