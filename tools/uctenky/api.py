"""Klient serverového API pro worker (docs/ai.md, „Účtenky").

Autentizace strojovým tokenem `kcw_…` (auth.api_token) — worker nemá účet uživatele.
Server posílá relativní cesty k obrázkům, base URL si worker skládá sám.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status


class WorkerApi:
    def __init__(self, base_url: str, token: str, timeout: int = 60) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def lease(self, limit: int = 1) -> list[dict]:
        return self._request("POST", f"/api/worker/receipts/lease?limit={limit}") or []

    def image(self, path: str) -> bytes:
        return self._request("GET", path, raw=True)

    def result(self, receipt_id: int, payload: dict) -> None:
        self._request("POST", f"/api/worker/receipts/{receipt_id}/result", payload)

    def fail(self, receipt_id: int, error: str, lease_until: str | None = None) -> None:
        # leaseUntil z lease určuje pronájem — server pak ignoruje hlášení ze staršího pronájmu.
        body = {"error": error[:500], "leaseUntil": lease_until}
        self._request("POST", f"/api/worker/receipts/{receipt_id}/fail", body)

    def archive_candidates(self, limit: int = 20) -> list[dict]:
        return self._request("GET", f"/api/worker/receipts/archive-candidates?limit={limit}") or []

    def archived(self, receipt_id: int) -> None:
        self._request("POST", f"/api/worker/receipts/{receipt_id}/archived")

    def _request(self, method: str, path: str, body: dict | None = None, raw: bool = False):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                content = response.read()
        except urllib.error.HTTPError as error:
            raise ApiError(error.code, error.read().decode("utf-8", "replace")) from error
        if raw:
            return content
        return json.loads(content.decode("utf-8")) if content else None
