"""OCR jedné části účtenky přes lokální Ollamu — VÝHRADNĚ obrázek -> text.

Žádná post-korekce textu modelem ani „vytažení struktury" modelem — obojí na reálných
účtenkách tiše měnilo množství a názvy (normalize.py). Strukturu dělají regexy.

Extraktor je za malým rozhraním (`Extractor.ocr`), aby šel později vyměnit (jiný model,
jiná služba) bez zásahu do zbytku workeru. Cloudovou službu ale zapnout jen s novou verzí
souhlasu a změnou zásad (docs/soukromi.md, „Účtenky").
"""

from __future__ import annotations

import base64
import json
import urllib.error
import urllib.request
from typing import Protocol

# glm-ocr (zai-org/GLM-OCR) je pod licencí MIT, tedy v souladu s pravidlem projektu
# "pouze svobodné licence" (CLAUDE.md, docs/ai.md — Hardware a volba modelu).
DEFAULT_MODEL = "glm-ocr:bf16"
DEFAULT_HOST = "http://localhost:11434"
DEFAULT_PROMPT = "Text Recognition: rozpoznej text na tomto obrázku v češtině"
# Kontext (tokeny obrázku + vygenerovaný text). Výchozí kontext Ollamy je 4096 a obrázek
# celé účtenky sám zabere ~4050 tokenů (ruční fotka bez výřezů), takže se buď nevešel vůbec
# (HTTP 400 „exceeds the available context size"), nebo zbylo na přepis pár desítek tokenů
# a text se potichu usekl. Model unese 131 072; 16 384 stačí na obrázek i dlouhou účtenku.
DEFAULT_NUM_CTX = 16384


class OcrError(RuntimeError):
    """Ollama odmítla požadavek — zpráva nese její důvod, ne jen „Bad Request"."""


class Extractor(Protocol):
    model: str

    def ocr(self, image: bytes) -> str: ...


class OllamaOcr:
    def __init__(self, host: str = DEFAULT_HOST, model: str = DEFAULT_MODEL,
                 prompt: str = DEFAULT_PROMPT, timeout: int = 600,
                 num_ctx: int = DEFAULT_NUM_CTX) -> None:
        self.host = host.rstrip("/")
        self.model = model
        self.prompt = prompt
        self.timeout = timeout
        self.num_ctx = num_ctx

    def ocr(self, image: bytes) -> str:
        payload = {
            "model": self.model,
            "prompt": self.prompt,
            "images": [base64.b64encode(image).decode("ascii")],
            "stream": False,
            # Nulová teplota a pevný seed — dvakrát spuštěné OCR má dát stejný text,
            # jinak nejde ladit parser proti stabilnímu vstupu.
            "options": {"temperature": 0, "seed": 0, "num_ctx": self.num_ctx},
            "keep_alive": "5m",
        }
        request = urllib.request.Request(
            f"{self.host}/api/generate",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return json.loads(response.read().decode("utf-8"))["response"]
        except urllib.error.HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise OcrError(f"Ollama HTTP {error.code} (obrázek {len(image)} B): {body[:300]}") from error
