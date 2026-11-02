"""Klient serverového API pro worker (docs/ai.md, „Účtenky").

Autentizace strojovým tokenem `kcw_…` (auth.api_token) — worker nemá účet uživatele.
Server posílá relativní cesty k obrázkům, base URL si worker skládá sám.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"HTTP {status}: {body[:300]}")
        self.status = status
        self.body = body

    def json(self) -> dict | None:
        try:
            return json.loads(self.body)
        except ValueError:
            return None


class _Client:
    def __init__(self, base_url: str, token: str, timeout: int = 60) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout

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


class WorkerApi(_Client):
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


class CuratorApi(_Client):
    """Kurátor označení (curate.py) — token s rozsahem LABEL_CURATOR, ne token workeru."""

    def unmatched(self, chain_id: int | None = None, limit: int = 200) -> list[dict]:
        query = f"?limit={limit}" + (f"&chainId={chain_id}" if chain_id else "")
        return self._request("GET", f"/api/curator/labels/unmatched{query}") or []

    def search_products(self, query: str, store_id: int | None, limit: int = 8) -> list[dict]:
        params = {"q": query, "limit": limit}
        if store_id:
            params["storeId"] = store_id
        return self._request("GET", "/api/curator/products/search?" + urllib.parse.urlencode(params)) or []

    def map_label(self, label: str, chain_id: int | None, store_id: int | None, product_id: int,
                  replace: bool = False) -> dict:
        """Výsledek mapování; konflikt (409) se vrací jako výsledek, ne výjimka."""
        body = {"label": label, "chainId": chain_id, "storeId": store_id, "productId": product_id,
                "replace": replace}
        try:
            return self._request("POST", "/api/curator/labels", body)
        except ApiError as error:
            if error.status in (400, 409) and error.json():
                return error.json()
            raise
