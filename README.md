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
docker compose up -d                              # PostgreSQL na 127.0.0.1:5437
cd backend && ./gradlew bootRun                   # backend na :8080
docker compose exec -T postgres psql -U postgres -d kvalitaacena < dev/seed.sql   # ukázková data
source ~/.nvm/nvm.sh && nvm use 24                # Angular 22 potřebuje Node ≥ 22.22.3
cd frontend && npm install && npm start           # web na :4200
```

Mobilní appka (Android) se staví přes `cd mobile && ./gradlew :app:assembleDebug`.

Podrobný návod — přihlášení (OTP kód se v etapě 1 čte z logu backendu), GraphiQL dotazy, spuštění
mobilu v emulátoru a řešení běžných potíží — je v [`docs/spusteni.md`](docs/spusteni.md).
Podrobnosti k vývoji a architektuře jsou v [`CLAUDE.md`](CLAUDE.md).

## Rychlý stop

Frontend i backend se ukončí `Ctrl+C` v jejich terminálu. Databáze v Dockeru běží dál na
pozadí — backend si ji sice sám spustí, ale sám ji nezastaví (`lifecycle-management:
start-only`), takže port `5437` zůstane obsazený, dokud ji nezastavíš ručně:

```bash
docker compose stop                               # zastaví PostgreSQL, data zůstanou
docker compose down                               # + odstraní kontejner (volume s daty zůstává)
docker compose down -v                            # + smaže i data — příště se seed pouští znovu
adb emu kill                                      # ukončí případný běžící emulátor
```

## Licence

Kód je pod [GNU AGPL-3.0](LICENSE). Kdo aplikaci provozuje jako veřejnou službu, musí
zpřístupnit i své úpravy zdrojového kódu.

## Licence knihoven

Používají se výhradně svobodné licence (MIT, Apache-2.0, BSD, EPL). Katalog zboží se smí obohatit
z Open Food Facts (ODbL) a mapové podklady z OpenStreetMap (ODbL), ale tato data jsou v databázi
oddělená od vlastních dat, aby se share-alike podmínka nepřenášela na zbytek databáze — viz
[`docs/datovy-model.md`](docs/datovy-model.md).
