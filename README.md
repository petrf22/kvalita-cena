# Kvalita a cena

Komunitní aplikace pro sledování cen běžného zboží (chléb, máslo, rohlíky, ...) v obchodech.
Uživatelé naskenují mobilem čárový kód, zapíšou cenu a obchod; web ukazuje aktuální ceny, vývoj
v čase a průměry napříč obchody. Vedle řetězců se zobrazují i lokální dodavatelé — nejde jen
o cenu, ale i o kvalitu a lokálnost.

Cíl: dát lidem přehled o cenách, aniž by je aplikace sama sledovala. Pozice a nákupní zvyklosti
uživatelů se neukládají — ukládá se jen to, co je nutné pro fungování komunity (viz
[`docs/soukromi.md`](docs/soukromi.md)).

## Struktura repozitáře

- `backend/` — Spring Boot 4 (Java 25), PostgreSQL, GraphQL API
- `frontend/` — Angular 22 + ng-zorro-antd, webové rozhraní
- `mobile/` — nativní Android (Kotlin + Jetpack Compose), skenování čárových kódů
- `docs/` — datový model, výpočet reputace, zásady soukromí
- `compose.yaml` — PostgreSQL pro lokální vývoj

## Rychlý start

```bash
docker compose up -d                 # PostgreSQL na 127.0.0.1:5437
cd backend && ./gradlew bootRun      # backend na :8080
cd frontend && npm install && npm start   # web na :4200
```

Podrobnosti k vývoji a architektuře jsou v [`CLAUDE.md`](CLAUDE.md).

## Licence

Kód je pod [GNU AGPL-3.0](LICENSE). Kdo aplikaci provozuje jako veřejnou službu, musí
zpřístupnit i své úpravy zdrojového kódu.

## Licence knihoven

Používají se výhradně svobodné licence (MIT, Apache-2.0, BSD, EPL). Katalog zboží se smí obohatit
z Open Food Facts (ODbL) a mapové podklady z OpenStreetMap (ODbL), ale tato data jsou v databázi
oddělená od vlastních dat, aby se share-alike podmínka nepřenášela na zbytek databáze — viz
[`docs/datovy-model.md`](docs/datovy-model.md).
