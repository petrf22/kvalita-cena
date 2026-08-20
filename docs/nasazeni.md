# Nasazení do produkce — co zbývá udělat ručně

Checklist věcí, které nejde (nebo nemá smysl) udělat v kódu předem — buď proto, že závisí na
konkrétním poskytovateli, který ještě není vybraný, nebo proto, že jde o skutečný krok mimo
repozitář (platba, DNS záznam, e-mailová schránka). Fáze 0 a Dockerfily/`compose.prod.yaml`
jsou hotové (`docs/vydani.md` řeší mobilní release/podpis, tenhle dokument produkční hosting
backendu a webu).

Odškrtávej rovnou v tomhle souboru a commituj — až bude všechno hotové, dá se to smazat nebo
přesunout do `docs/vydani.md` jako historickou poznámku.

## 1. Nezávislé na poskytovateli (jde udělat kdykoli předem)

- [ ] **Zřídit `kontakt@kvalitacena.cz`** — přesměrování na skutečnou schránku. Bez něj jsou
  `docs/podminky-uziti.md` a `docs/zasady-ochrany-osobnich-udaju.md` (i odkazy v appce)
  neplatné — GDPR žádosti a kontaktní e-mail v podmínkách na něj cílí. Adresu teď uvádí i
  stránka „O aplikaci" (web `features/about`, mobil `ui/about/AboutScreen.kt`), ne jen oba
  právní dokumenty.
- [ ] **Doplnit datum účinnosti** v obou dokumentech výš (zatím placeholder
  `[DOPLNIT DATUM ZVEŘEJNĚNÍ]`) — nastavit na den, kdy appka skutečně půjde do provozu (uzavřená
  beta podle plánu), ne dřív.
- [ ] **Vygenerovat produkční tajemství** — nezávisle na hostingu, dá se udělat kdykoli:
  ```
  openssl rand -base64 32   # spustit 3× zvlášť pro JWT_SECRET / EMAIL_HASH_PEPPER / EMAIL_ENC_KEY
  ```
  Uložit **mimo repo i mimo tenhle stroj** (heslo manažer / šifrovaný trezor), stejný režim
  jako podpisový klíč mobilní appky (`docs/vydani.md`). **`EMAIL_HASH_PEPPER` musí být finální
  před prvním reálným účtem** — pozdější změna = nikdo se nepřihlásí (`docs/soukromi.md`).
- [ ] **Vybrat SMTP poskytovatele pro OTP e-maily** (přihlašovací kódy) — nízkoobjemový provoz,
  free tier stačí. Nepoužívat vlastní SMTP na VPS (skončí ve spamu). Kandidáti k porovnání:
  Resend, Postmark, Amazon SES, Mailgun — u všech potřeba: API/SMTP přihlašovací údaje +
  ověřená odesílací doména.
- [x] **VPS poskytovatel: Hetzner Cloud.** Nejlevnější spolehlivá varianta s perzistentním
  diskem (appka fotky drží mimo databázi na disku, `docs/datovy-model.md` — PaaS s efemérním
  filesystémem jako Cloud Run/Heroku nejde použít), servery v EU (Německo/Finsko — sedí
  k tomu, jak appka od začátku řeší lokalitu dat), nejmenší instance řádově 4–5 €/měsíc =
  přesně těch 120–250 Kč z odhadu níž. Založit účet, nejmenší instanci s KVM + SSD.
- [ ] **Vybrat cíl pro offsite zálohu** (jiný poskytovatel než Hetzner výš, ať jeden výpadek
  nevezme obojí) — B2/S3-kompatibilní úložiště s levným cold storage stačí, appka zálohuje jen
  `pg_dump` + adresář `app.media.root`. Hetzner má vlastní Storage Box, ale pro skutečnou
  redundanci je lepší jiný poskytovatel (např. Backblaze B2) — nerozhodnuto.

## 2. Po výběru VPS

- [ ] Založit server, nainstalovat Docker + Docker Compose.
- [ ] **DNS záznamy** na doméně `kvalitacena.cz` (u registrátora domény, ne u VPS):
  - `A`/`AAAA` `kvalitacena.cz` → IP serveru (web, Caddy)
  - `A`/`AAAA` `api.kvalitacena.cz` → stejná IP (mobil, viz `mobile/app/build.gradle.kts`)
- [ ] Zkopírovat `.env.example` → `.env` na server, doplnit:
  - `POSTGRES_USER`/`POSTGRES_PASSWORD` — libovolné silné heslo, jen pro appku samotnou
  - `JWT_SECRET`/`EMAIL_HASH_PEPPER`/`EMAIL_ENC_KEY` — hodnoty vygenerované v kroku 1 výš
  - `SITE_ADDRESS=https://kvalitacena.cz`
  - `API_ADDRESS=https://api.kvalitacena.cz` — vlastní site blok v `frontend/Caddyfile` pro
    mobilní klienty, bez něj Caddy pro tenhle hostname nezíská TLS certifikát
- [ ] `git clone` repa na server, `docker compose -f compose.prod.yaml up -d --build`.
- [ ] Ověřit, že Caddy dostal TLS certifikát (Let's Encrypt, automaticky) pro OBĚ domény —
  `https://kvalitacena.cz` i `https://api.kvalitacena.cz`
  musí být bez varování prohlížeče.
- [ ] Nastavit cron zálohu (`pg_dump` + `rsync`/snapshot adresáře médií na cíl z kroku 1) —
  a **vyzkoušet obnovu** na čistou instanci, ne jen že záloha proběhla bez chyby.

## 3. Po výběru SMTP poskytovatele

- [ ] Doplnit do `.env` na serveru: `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`.
- [ ] U poskytovatele ověřit odesílací doménu `kvalitacena.cz` a nastavit **SPF, DKIM, DMARC**
  (poskytovatel dá přesné DNS záznamy k vložení) — bez nich OTP e-maily končí ve spamu nebo se
  vůbec nedoručí.
- [ ] Doplnit `NOMINATIM_USER_AGENT` do `.env` s reálným kontaktem (`kontakt@kvalitacena.cz`) —
  vyžaduje to usage policy OpenStreetMap Nominatim, jinak riziko zablokování IP serveru.
- [ ] Restartovat appku (`docker compose -f compose.prod.yaml up -d`), ověřit doručení testovacím
  přihlášením na skutečnou schránku (ne do spamu).

## 4. Než pozvat první lidi (uzavřená beta, Fáze 2 plánu) — kód hotový, zbývá jen zapnout

`frontend/public/robots.txt` (zákaz indexace) a `backend/.../application-beta.yml` (prahy
důvěry na 0/0/1 pro OSOBNĚ pozvané lidi) jsou v repu hotové. Zbývá:

- [ ] V `.env` na serveru nastavit `SPRING_PROFILES_ACTIVE=prod,beta` (viz `.env.example`) a
  restartovat (`docker compose -f compose.prod.yaml up -d`) — profil `beta` se dá kdykoli
  vypnout zpět na `prod` bez editace kódu.
- [ ] **Označit sebe (a případně dalšího důvěryhodného člověka) jako moderátora** — nástroj
  pro přezkum nahlášených záznamů (`docs/reputace.md`, „Moderace", T4) je hotový, ale appka
  nemá UI na jmenování, jen ruční SQL na serveru:
  ```sql
  UPDATE auth.app_user SET is_moderator = true WHERE public_handle = '<handle z /moderation nebo DB>';
  ```
  Bez aspoň jednoho moderátora nemá nahlášený obsah (fotky, zboží, obchody) kdo přezkoumat —
  udělat PŘED pozváním prvních lidí, ne až po prvním nahlášení.
- **Číselník kategorií zboží byl bez seedu úplně prázdný** — objevilo se to při prvním ručním
  testu 2026-08-19: `CreateProductInput.categoryId` je v GraphQL schématu povinné (`ID!`) a
  žádná `createCategory` mutace neexistuje (kategorie je fixní číselník, ne uživatelský obsah
  jako zboží/obchod), takže appka bez seedu nešla reálně použít — nedalo se založit jediné
  zboží. Opraveno migrací `backend/.../db/changelog/2026-08-19/01-category-seed.yaml` (startovní
  sada 24 kategorií), následně `2026-08-20/01-category-tree.yaml` ji rozšířila na plný strom pro
  běžný supermarket (~106 kategorií, šest kořenů, přeložený do sk/en/pl/de —
  `docs/lokalizace.md`, „Kategorie"). Obě proběhnou automaticky s dalším nasazením, žádný ruční
  krok navíc netřeba. Další rozšíření číselníku i filtr hledání podle kategorie zůstávají
  budoucí práce (`docs/rozvoj.md`).
- **Katalog obchodů se záměrně nepředvyplňuje** — zvažovalo se ruční přepsání poboček
  velkých řetězců (COOP, Penny) i hromadný import z OpenStreetMap, ale u objemu, o který by
  reálně šlo (tisíce poboček napříč velkými řetězci), obojí naráží na stejné riziko z druhé
  strany: scraping webů řetězců je právně nejistý kvůli sui generis právu k databázi
  (směrnice 96/9/ES) a hromadný import z OSM/Overpass do `core.store` by byl „substantial
  part" cizí ODbL databáze zkopírované do appčiných vlastních, uzavřených dat — přesně to,
  čemu měl rozestup `core`/`osm` v `docs/datovy-model.md` zabránit, jen z opačné strany než
  scraping. Katalog roste organicky s uživateli, jak appka byla navržená; studený start řeší
  dočasně snížené prahy důvěry (`application-beta.yml`), ne předpřipravená data. Jediná cesta
  ke skutečně velkému pokrytí by bylo naostro postavit `osm.*` schéma se sync jobem (čtení
  spojené s `core.*` až za běhu, stejný princip jako appka má pro Open Food Facts) —
  samostatná vícedenní architektonická práce, ne krok téhle bety.
- [x] **Kanál zpětné vazby** — appka dřív neměla žádný způsob, jak od testerů dostat hlášení,
  jen `mailto:kontakt@kvalitacena.cz` na „O aplikaci" (a ta schránka pořád není zřízená, viz
  bod 1 výš — pořád nutná jako záložní kanál pro GDPR žádosti, in-app formulář ji nenahrazuje).
  In-app formulář (`core.feedback`, funguje i bez přihlášení, `docs/datovy-model.md`, „Zpětná
  vazba") na webu i Androidu je hotový, fronta pro provozovatele je čtvrtá záložka na
  `/moderation`. Nic dalšího tu nezbývá zapnout.
- [ ] **Před Fází 3** (veřejné spuštění): vrátit `SPRING_PROFILES_ACTIVE` zpět na jen `prod`
  a `frontend/public/robots.txt` buď smazat, nebo povolit indexaci (`Disallow:` prázdné) —
  jinak appka po zveřejnění zůstane neviditelná pro vyhledávače a prahy důvěry 0/0/1 by
  fungovaly i pro veřejnost, ne jen pozvané lidi.
- [ ] **Před Fází 3: posílit obranu formuláře zpětné vazby proti spamu.** Dnešní obrana
  (`FeedbackRateLimiter`, `app.feedback.max-per-day-per-ip: 20`) stačí na uzavřenou betu
  s osobně pozvanými lidmi, ale ne na veřejný formulář dostupný komukoli:
  - 20 odeslání/den na IP je velkorysé pro anonymní útočníka z jedné IP; proti
    distribuovanému spamu (víc IP) appka nemá vůbec nic.
  - žádný CAPTCHA/honeypot — appka nerozezná bota od člověka.
  - na rozdíl od `core.record_flag` (`app.moderation.flags-to-hide`) nemá `core.feedback`
    žádné automatické skrytí/prioritizaci — při náporu by fronta na `/moderation` rychle
    zavalila jediného moderátora (`docs/soukromi.md`, „kapacita moderace jednoho člověka").
  Řešit až tady, ne dřív — do té doby appku nikdo zvenčí nenajde (`robots.txt` výš).

### Protokol bety — koho pozvat a co s nálezy

Profil `beta` (prahy 0/0/1) dává smysl jen pro OSOBNĚ pozvané lidi, ne pro veřejnou výzvu —
s cizími lidmi bez vztahu k appce by studený start dopadl stejně jako bez sníženého prahu,
jen s hůř diagnostikovatelnými nálezy. Doporučený postup:

1. Pozvat osobně (zprávou/e-mailem s odkazem), ne veřejným příspěvkem — desítky lidí, ne
   stovky, ať zůstane kapacita moderace i na zpětnou vazbu zvládnutelná (`docs/reputace.md`,
   „Otevřená rizika").
2. Každému dát stejný minimální scénář, ať appka projde celý tok, ne jen náhodné klikání:
   přihlásit se (OTP), najít zboží podle jména i skenem, zapsat cenu z mobilu i z webu,
   založit chybějící obchod, nahrát fotku, zkusit `/feedback` schválně (i s prázdnou zprávou).
3. Ozvat se každému znovu přibližně jednou týdně — ne čekat, až se sami ozvou. Zpětná vazba
   z formuláře (`/moderation`, záložka „Zpětná vazba") je jeden zdroj, přímá zpráva testerovi
   „jak to jde" druhý — appka první beta test (19. 8. 2026, prázdný číselník kategorií, viz
   výš) našla ručním klikáním, ne čekáním na formulář, který tou dobou ještě neexistoval.
4. Nálezy, které nejsou jen jednotlivé hlášení k vyřízení, ale mění, co appka ještě
   potřebuje (jako prázdný číselník kategorií), zapisovat rovnou sem do `docs/nasazeni.md`
   stejným stylem jako existující položky — ne nechat je zapadnout v chatu/paměti.

### Provozní přehled bez analytiky

Appka nemá (a mít nesmí, `docs/soukromi.md`) žádnou analytiku — signál „appka se používá/
appka se nepoužívá" jde jen přímým dotazem do databáze. `dev/beta-report.sql` je sada
dotazů pro rychlou kontrolu při běžícím testu (kolik lidí zapsalo cenu za posledních 7 dní,
kolik zboží/obchodů visí v `DRAFT` déle než pár dní, kolik nahlášení a kolik zpětné vazby
čeká na vyřízení):

```bash
docker compose exec -T postgres psql -U postgres -d kvalitaacena < dev/beta-report.sql
```

---

Až bude hotovo, tenhle dokument buď smazat, nebo přesunout obsah (co bylo skutečně zvoleno —
poskytovatel, region serveru) do `docs/vydani.md` jako trvalý záznam rozhodnutí, stejně jako
tam je zapsaný postup pro podpisový klíč mobilní appky.
