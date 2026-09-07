#!/usr/bin/env python3
"""Sekvenční dávkové OCR účtenek přes lokální Ollamu.

    python3 tools/uctenky/ocr.py --dir uctenky

Ke každému obrázku vznikne <jmeno>.ocr.txt (syrový text z modelu) a <jmeno>.ocr.json
(model, prompt, otisk obrázku, trvání). Hotové soubory se přeskakují, takže se dávka
dá kdykoli přerušit a spustit znovu; --force přepíše.

Model dělá VÝHRADNĚ obrázek -> text. Žádná post-korekce textu modelem — viz normalize.py.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# glm-ocr (zai-org/GLM-OCR) je pod licencí MIT, tedy v souladu s pravidlem projektu
# "pouze svobodné licence" (CLAUDE.md, docs/ai.md — Hardware a volba modelu).
DEFAULT_MODEL = "glm-ocr:bf16"
DEFAULT_HOST = "http://localhost:11434"
DEFAULT_PROMPT = "Text Recognition: rozpoznej text na tomto obrázku v češtině"
IMAGE_SUFFIXES = (".png", ".jpg", ".jpeg", ".webp")


def _generate(host: str, model: str, prompt: str, image: bytes, timeout: int) -> str:
    payload = {
        "model": model,
        "prompt": prompt,
        "images": [base64.b64encode(image).decode("ascii")],
        "stream": False,
        # Nulová teplota a pevný seed — dvakrát spuštěné OCR má dát stejný text,
        # jinak nejde ladit parser proti stabilnímu vstupu.
        "options": {"temperature": 0, "seed": 0},
        "keep_alive": "5m",
    }
    request = urllib.request.Request(
        f"{host.rstrip('/')}/api/generate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))["response"]


def ocr_image(source: Path, host: str, model: str, prompt: str, timeout: int) -> tuple[str, dict]:
    image = source.read_bytes()
    started = time.monotonic()
    text = _generate(host, model, prompt, image, timeout)
    return text, {
        "model": model,
        "imageSha256": hashlib.sha256(image).hexdigest(),
        "ocrAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "durationMs": int((time.monotonic() - started) * 1000),
        "prompt": prompt,
        "sourceFile": source.name,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Dávkové OCR účtenek přes Ollamu")
    parser.add_argument("images", nargs="*", type=Path, help="konkrétní obrázky")
    parser.add_argument("--dir", type=Path, help="adresář s obrázky účtenek")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--prompt", default=DEFAULT_PROMPT)
    parser.add_argument("--timeout", type=int, default=600, help="sekund na jeden obrázek")
    parser.add_argument("--force", action="store_true", help="přepíše i hotové výstupy")
    args = parser.parse_args()

    targets = sorted(args.images)
    if args.dir:
        targets += sorted(
            path for path in args.dir.iterdir() if path.suffix.lower() in IMAGE_SUFFIXES
        )
    if not targets:
        parser.error("zadej obrázky nebo --dir")

    failed = 0
    for source in targets:
        target = source.with_suffix(".ocr.txt")
        if target.exists() and not args.force:
            print(f"-- {source.name}: hotovo, přeskakuji")
            continue
        try:
            text, meta = ocr_image(source, args.host, args.model, args.prompt, args.timeout)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            print(f"!! {source.name}: {error}", file=sys.stderr)
            failed += 1
            continue
        target.write_text(text.strip() + "\n", encoding="utf-8")
        target.with_suffix(".json").write_text(
            json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print(f"OK {source.name}: {len(text.splitlines())} řádků za {meta['durationMs']} ms")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
