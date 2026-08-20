# CLAUDE.md — frontend

Konvence a příkazy specifické pro `frontend/` (Angular 22 + ng-zorro-antd). Vývojářský přehled
je v [`frontend/README.md`](README.md); cross-cutting pravidla (jazyk komentářů, licence
knihoven, architektura sdílená napříč aplikacemi) jsou v kořenovém
[`CLAUDE.md`](../CLAUDE.md).

## Příkazy

Vyžaduje Node ≥ 22.22.3 (Angular 22) — systémový Node je 22.17.0, starý na to. Aktivuj přes
nvm: `source ~/.nvm/nvm.sh && nvm use 24` (Node 24 je nainstalované vedle systémového, výchozí
Node se neměnil — viz `.bashrc`). `npm start` používá `proxy.conf.json`, který přeposílá
`/api` a `/graphql` na `localhost:8080`, takže backend musí běžet zároveň.

```bash
npm install
npm run codegen                      # typy z backend/.../schema.graphqls do models/generated/ (viz codegen.ts)
npm start                            # dev server na :4200 (s proxy na backend)
npm test                             # Vitest (Angular 22 default, ne Karma/Jasmine)
npm run build
```

`npm run codegen` spusť po každé změně `schema.graphqls` nebo dotazu v `graphql(...)` volání —
výstup v `src/app/models/generated/` se commituje, CI ho přegeneruje a shodí build, pokud se
rozejde (`git diff --exit-code`). Pozor na pořadí: `graphql(...)` matchuje dotaz na přesný
string zachycený při generování, takže Prettier (nebo jakákoli jiná změna whitespace uvnitř
těch template literálů) musí proběhnout **před** posledním `npm run codegen`, jinak typová
kontrola i běhový match spadnou.

## Konvence

- Standalone komponenty, signály, bez state managementu, bez Apollo v runtime
  (`provideHttpClient` s funkcionálními interceptory, jeden POST `/graphql` v
  `services/graphql-service.ts`) — typy a tvary dotazů generuje `graphql-codegen` ze
  `schema.graphqls` do `src/app/models/generated/` (`npm run codegen`, commituje se), `graphql`
  balíček je tak jen build-time závislost, ne runtime; `LOCALE_ID: 'cs-CZ'`; Prettier
  `printWidth: 100`, `singleQuote: true`
- **Lokalizace** (`../docs/lokalizace.md`): nepoužívá `CurrencyPipe`/`DatePipe`/`DecimalPipe`
  (formátování jde přes `FormatService` nad `Intl.*`, protože `LOCALE_ID` se vyhodnocuje jen
  jednou při bootstrapu a měna přichází z dat, ne z locale); routy jsou anglické a jazykově
  neutrální, české cesty jsou jen redirecty; pole `ico` v GraphQL je název z historie (nese IČO
  i NIP), validace i popisek jdou per `country`
- Odsazení 2 mezery — viz `.editorconfig`
