# Frontend

Angular 22 + ng-zorro-antd webové rozhraní projektu Kvalita a cena.

Vyžaduje Node ≥ 22.22.3 — systémový Node bývá starší, aktivuj novější přes
`source ~/.nvm/nvm.sh && nvm use 24`.

```bash
npm install
npm start            # dev server na :4200, proxuje /api a /graphql na backend :8080
npm test -- --watch=false
npm run build
```

Kompletní návod ke spuštění celého stacku (backend, ukázková data, přihlášení, mobil) je
v [`../docs/spusteni.md`](../docs/spusteni.md), architektura a konvence v [`../CLAUDE.md`](../CLAUDE.md).
