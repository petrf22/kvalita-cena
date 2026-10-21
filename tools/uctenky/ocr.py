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
import urllib.request
from typing import Protocol

# glm-ocr (zai-org/GLM-OCR) je pod licencí MIT, tedy v souladu s pravidlem projektu
# "pouze svobodné licence" (CLAUDE.md, docs/ai.md — Hardware a volba modelu).
DEFAULT_MODEL = "glm-ocr:bf16"
DEFAULT_HOST = "http://localhost:11434"
DEFAULT_PROMPT = "Text Recognition: rozpoznej text na tomto obrázku v češtině"


class Extractor(Protocol):
    model: str

    def ocr(self, image: bytes) -> str: ...


class OllamaOcr:
    def __init__(self, host: str = DEFAULT_HOST, model: str = DEFAULT_MODEL,
                 prompt: str = DEFAULT_PROMPT, timeout: int = 600) -> None:
        self.host = host.rstrip("/")
        self.model = model
        self.prompt = prompt
        self.timeout = timeout

    def ocr(self, image: bytes) -> str:
        payload = {
            "model": self.model,
            "prompt": self.prompt,
            "images": [base64.b64encode(image).decode("ascii")],
            "stream": False,
            # Nulová teplota a pevný seed — dvakrát spuštěné OCR má dát stejný text,
            # jinak nejde ladit parser proti stabilnímu vstupu.
            "options": {"temperature": 0, "seed": 0},
            "keep_alive": "5m",
        }
        request = urllib.request.Request(
            f"{self.host}/api/generate",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return json.loads(response.read().decode("utf-8"))["response"]
