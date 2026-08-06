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

## Visual Studio Code

Otevírej **tuhle složku** (`code frontend/`), ne kořen repozitáře — konfigurace v `.vscode/` na
tom závisí (cesty, doporučená rozšíření). `.nvmrc` s verzí `24` zajišťuje, že si `nvm use` bez
argumentu (i v terminálu, i v úlohách níže) sáhne na stejného Node jako zbytek projektu.

- **F5** — `Web: dev server + Chrome`: nastartuje `npm start` a otevře `http://localhost:4200/`
  v ladicím Chromu (breakpointy fungují přímo v TS/HTML zdrojích). `Web: Chrome (server už
  běží)` je stejné, jen bez spouštění serveru — použij, když ho máš puštěný v terminálu.
  `Testy: Vitest (ladění)` spustí `ng test --watch=false` s připojeným debuggerem.
- **Ctrl+Shift+B** — `web: build` (`npm run build`).
- **Ctrl+Shift+P → Tasks: Run Task** — dál `web: testy (watch)`, `web: testy (jednorázově)`,
  `web: install`, `backend: bootRun` (spustí `../backend` přes Gradle wrapper) a
  `stack: backend + web`, která nastartuje obojí najednou.
- Backend musí běžet, jinak `/api` a `/graphql` z proxy vrací chyby — buď z IntelliJ, nebo
  úlohou `backend: bootRun`.
- Formátování při uložení jede přes Prettier (`.prettierrc`) — vyžaduje rozšíření
  `esbenp.prettier-vscode`, doporučené v `extensions.json`.
