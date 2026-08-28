# Nasazení do produkce — co zbývá udělat ručně

Checklist věcí, které nejde (nebo nemá smysl) udělat v kódu předem — buď proto, že závisí na
konkrétním poskytovateli, který ještě není vybraný, nebo proto, že jde o skutečný krok mimo
repozitář (platba, DNS záznam, e-mailová schránka). Fáze 0 a Dockerfily/`compose.prod.yaml`
jsou hotové (`docs/vydani.md` řeší mobilní release/podpis, tenhle dokument produkční hosting
backendu a webu).

Odškrtávej rovnou v tomhle souboru a commituj — až bude všechno hotové, dá se to smazat nebo
přesunout do `docs/vydani.md` jako historickou poznámku.

**Pořadí sekcí 1–4 je tematické, ne sekvenční — tři závislosti mezi nimi jsou tvrdé a nevratné:**

1. **Datum účinnosti (sekce 1) musí být nasazené DŘÍV, než vznikne první účet (sekce 4).** JIT
   registrace při první úspěšné verifikaci OTP zapisuje `terms_accepted_at`/`terms_version` —
   účet založený na buildu s `[DOPLNIT DATUM ZVEŘEJNĚNÍ]` má souhlas s dokumentem bez data
   účinnosti, opravitelné jen novou verzí podmínek, ne editací starého data zpětně.
2. **`EMAIL_HASH_PEPPER` (sekce 1) musí být finální před úplně prvním účtem, včetně vlastního.**
   Pozdější změna hodnoty = ten účet je navždy nepřihlásitelný (viz sekce 1 níž).
3. **Bez fungujícího SMTP (sekce 3) se nepřihlásí nikdo, včetně provozovatele** — appka
   nastartuje i s neplatnými SMTP údaji, ale `POST /api/auth/otp/request` je transakční a
   odeslání e-mailu je uvnitř transakce; chyba SMTP shodí celý požadavek dřív, než se výzva
   vůbec uloží. Nemá to obchvat přes DB (žádný seed účet), jen dočasnou berličku popsanou
   v sekci 3.

Doporučené pořadí prací: rozjet server (sekce 2) proti IP, ne proti doméně, ať se nejpravděpodobnější
selhání (build appky na serveru, viz sekce 2) neřeší zároveň s laděním DNS a Let's Encrypt limitů;
teprve pak přepnout DNS a TLS; SMTP (sekce 3, poskytovatel je už vybraný — Gigaserver) doplnit
spolu s TLS krokem; datum účinnosti a zapnutí bety (sekce 1 a 4) nechat jako poslední krok těsně
před první pozvánkou.

## 1. Nezávislé na poskytovateli (jde udělat kdykoli předem)

- [x] **Zřídit `kontakt@kvalitacena.cz`** — hotovo 2026-08-22, schránka u Gigaserveru (tarif
  Smart), přesměrování na osobní Gmail. Heslo ke schránce je mimo repo (~/.config/kvalitacena),
  ne v `.env` — používá se jen jednorázově přes webmail/přeposílání, appka na ni sama nepíše.
  Tahle schránka je teď zároveň SMTP účet pro OTP e-maily, viz sekce 3.
- [x] **Doplnit datum účinnosti** — hotovo 2026-08-24, „Platí od 24. srpna 2026." na všech pěti
  místech (`docs/podminky-uziti.md`, `docs/zasady-ochrany-osobnich-udaju.md`,
  `frontend/.../terms-page.html`, `frontend/.../privacy-page.html`,
  `mobile/.../values/strings.xml`) — týž den, kdy appka reálně jde do uzavřené bety.
- [ ] **Vygenerovat produkční tajemství** — nezávisle na hostingu, dá se udělat kdykoli:
  ```
  openssl rand -base64 32   # spustit 3× zvlášť pro JWT_SECRET / EMAIL_HASH_PEPPER / EMAIL_ENC_KEY
  ```
  Uložit **mimo repo i mimo tenhle stroj** (heslo manažer / šifrovaný trezor), stejný režim
  jako podpisový klíč mobilní appky (`docs/vydani.md`). **`EMAIL_HASH_PEPPER` musí být finální
  před prvním reálným účtem** — pozdější změna = nikdo se nepřihlásí (`docs/soukromi.md`).
- [x] **SMTP pro OTP e-maily: Gigaserver, ne dedikovaný poskytovatel** — rozhodnuto 2026-08-22.
  Použije se schránka `kontakt@kvalitacena.cz` (sekce výš) jako SMTP účet, `mail.gigaserver.cz`
  port **587** (STARTTLS). Detaily a past s portem 465 viz sekce 3. Výměna za Resend/Postmark
  je odložená na „Před Fází 3" níž — dnes odpadá registrace i ověření odesílací domény.
- [x] **VPS poskytovatel: Hetzner Cloud**, ne Gigaserver (kde appka má doménu i e-mail) — záměrně
  jiný dodavatel, ať jeden výpadek nevezme server, doménu i kontaktní schránku pro GDPR žádosti
  naráz. Nejlevnější spolehlivá varianta s perzistentním diskem (appka fotky drží mimo databázi
  na disku, `docs/datovy-model.md` — PaaS s efemérním filesystémem jako Cloud Run/Heroku nejde
  použít), servery v EU (Německo/Finsko — sedí k tomu, jak appka od začátku řeší lokalitu dat).
  Odhad „4–5 €/měsíc" z dřívějška je po dubnovém zdražení Hetzneru (~+36 %, vyšší ceny DRAM)
  zastaralý — počítat spíš s 5–6 € + DPH. **Instance minimálně 2 vCPU / 4 GB RAM** (např. CX23
  nebo ARM CAX11) — ne kvůli běhu appky samotné, ale protože `compose.prod.yaml` staví backend
  i frontend přímo na serveru (Gradle + `ng build`); přidat i **2 GB swap** jako pojistku proti
  OOM při buildu, ať selhání vypadá jako pomalý build, ne jako nesouvisející pád. Založit účet,
  instanci s KVM + SSD. **Účet zřízený 2026-08-22**, instance zatím ne (sekce 2).
  **Past:** Hetzner blokuje odchozí porty **25 a 465** na všech cloud serverech (proti zneužití
  pro spam); odblokování jde žádat podporou až po měsíci provozu a první zaplacené faktuře.
  Port **587** blokovaný není — proto SMTP níž běží přes něj, ne přes 465 z údajů schránky.
- [ ] **Vybrat cíl pro offsite zálohu** (jiný poskytovatel než Hetzner výš, ať jeden výpadek
  nevezme obojí) — B2/S3-kompatibilní úložiště s levným cold storage stačí, appka zálohuje jen
  `pg_dump` + adresář `app.media.root`. Hetzner má vlastní Storage Box, ale pro skutečnou
  redundanci je lepší jiný poskytovatel (např. Backblaze B2) — nerozhodnuto.

## 2. Po výběru VPS

Doporučeno rozjet appku nejdřív **proti IP, bez DNS** (kroky 1–3 níže), a teprve pak přepnout
domény na server (krok 4) — build appky na serveru je nejpravděpodobnější místo prvního selhání
(viz „Sekvenční build" níž) a není důvod to ladit ve chvíli, kdy doména už míří na prázdnou IP.

1. [ ] Založit server, nainstalovat Docker + Docker Compose plugin, non-root uživatel se
   SSH klíčem, `ufw` (22/80/443), `unattended-upgrades`, **2 GB swap** (viz sekce 1 výš).
2. [ ] `git clone` repa na server, `cp .env.example .env`, doplnit:
   - `POSTGRES_USER`/`POSTGRES_PASSWORD` — libovolné silné heslo (bez znaku `$`, viz níž), jen
     pro appku samotnou
   - `JWT_SECRET`/`EMAIL_HASH_PEPPER`/`EMAIL_ENC_KEY` — hodnoty vygenerované v kroku 1 výš
   - `SITE_ADDRESS=http://<IP serveru>`, `API_ADDRESS` **zatím nevyplňovat** (default
     `http://api.localhost` v `.env.example`/`compose.prod.yaml`) — adresy s explicitním
     `http://` schématem Caddy nechá jen na `:80`, ACME se vůbec nespustí, takže tahle fáze
     nemůže vyčerpat žádný rate limit Let's Encrypt
   - `SPRING_PROFILES_ACTIVE=prod,beta` (viz sekce 4)

   **`POSTGRES_PASSWORD` generovat přes `openssl rand -hex 32`, ne `-base64 32`** — jde do
   `compose.prod.yaml` přes interpolaci `${POSTGRES_PASSWORD}`, kde znak `$` z base64 abecedy
   Compose sám expanduje. `JWT_SECRET`/`EMAIL_HASH_PEPPER`/`EMAIL_ENC_KEY` naproti tomu jdou přes
   `env_file` (literálně) — tam base64 s `+`/`/`/`=` vadit nebude, `EMAIL_ENC_KEY` ale musí po
   dekódování vyjít na přesně 16/24/32 bajtů.
3. [ ] **Sekvenční build** — ne jedno `up -d --build`, které staví backend (Gradle) i web
   (`npm ci` + `ng build`) paralelně a na malé instanci může spolu s Postgresem vyčerpat RAM.
   `GIT_SHA` je nepovinný, ale bez něj `/actuator/info` nese jen verzi bez commitu (`docs/
   vydani.md`, „Verzování a vydání"). `ops/deploy.sh` (viz `ops/README.md`) tenhle postup
   i ověření dělá automaticky pro vydané tagy; v týhle první fázi (repo ještě jen naklonované,
   žádný tag zatím nemusí sedět) je ruční varianta:
   ```bash
   export GIT_SHA=$(git rev-parse --short HEAD)
   docker compose -f compose.prod.yaml build backend
   docker compose -f compose.prod.yaml build web
   docker compose -f compose.prod.yaml up -d
   ```
   Ověřit: `curl http://<IP>/` (Angular index), `curl http://<IP>/graphql` (odpoví), v logu
   backendu (`docker compose -f compose.prod.yaml logs backend`) dokončený Liquibase bez chyb.
   Přihlášení v téhle fázi ověřit nejde — `cookie-secure: true` v produkčním profilu znamená, že
   se refresh cookie po obyčejném HTTP nenastaví.
4. [ ] **DNS záznamy** na doméně `kvalitacena.cz` (u registrátora domény, ne u VPS) — nejdřív
   snížit TTL na 300 s a počkat, až doběhne starý, pak přepsat:
   - `A`/`AAAA` `kvalitacena.cz` → IP serveru (web, Caddy)
   - `api.kvalitacena.cz` je dnes CNAME na `kvalitacena.cz` (ne vlastní `A` záznam) — propíše se
     samo, není potřeba měnit zvlášť
   - **MX ani SPF neměnit** — e-mail zůstává u dosavadního poskytovatele domény, viz sekce 3
   - Ověřit `dig +short kvalitacena.cz` a `dig +short api.kvalitacena.cz` z jiného stroje, než
     appka i doména budou skutečně souhlasit s IP serveru
5. [ ] Přepsat v `.env` `SITE_ADDRESS=https://kvalitacena.cz` a
   `API_ADDRESS=https://api.kvalitacena.cz`, `docker compose -f compose.prod.yaml up -d`
   (recreatne jen `web`). Ověřit, že Caddy dostal TLS certifikát (Let's Encrypt, automaticky) pro
   OBĚ domény — `https://kvalitacena.cz` i `https://api.kvalitacena.cz` musí být bez varování
   prohlížeče, včetně `https://api.kvalitacena.cz/actuator/health` → `{"status":"UP"}`.

   **Nikdy `docker compose -f compose.prod.yaml down -v`** — v `compose.prod.yaml` nejsou žádné
   externí volumes, smazalo by to certifikáty i celou produkční databázi naráz. Opakované mazání
   `caddy-data` navíc vyčerpá limit Let's Encrypt na 5 identických certifikátů týdně.
6. [x] Nastavit cron zálohu — `ops/backup.sh` (`pg_dump` + archiv adresáře médií; cíl offsite
   úložiště z kroku 1 výš zůstává jen komentovaný návod v skriptu, doplnit po výběru
   poskytovatele) a **vyzkoušet obnovu** na čistou instanci, hotovo 2026-08-24 podle
   `ops/README.md`. Zkouška odhalila a opravila reálný bug: `compose.prod.yaml` nechávalo
   volume `kvalita-a-cena-media-prod` bez `name:` přepisu, Docker Compose ji tak automaticky
   prefixoval podle názvu adresáře repa, zatímco `ops/backup.sh` čte holé jméno bez prefixu —
   zálohy fotek tak byly od nasazení appky prázdné, i když skript hlásil úspěch. Opraveno
   pojmenováním volume napevno (commit `9a7b338`); po nasazení opravy je nutné
   `docker compose -f compose.prod.yaml up -d --force-recreate backend`, prosté `up -d`
   běžící kontejner na novou volume nepřepojí.

## 3. SMTP pro OTP e-maily — Gigaserver (rozhodnuto 2026-08-22)

Rozhodnutí: použít rovnou schránku `kontakt@kvalitacena.cz` (sekce 1) jako SMTP účet, ne
zřizovat dedikovaného poskytovatele (Resend/Postmark/SES). Dvě zjištění, která to udělala
snazší volbou pro betu než plánovaná registrace u samostatného poskytovatele:

- `application-prod.yml` má `spring.mail.properties.mail.smtp.starttls.enable: true` a
  `auth: true`, ale ne `ssl.enable` — to je přesně konfigurace pro **port 587**, ne pro
  SSL port 465 z údajů schránky. Shodou okolností je to zároveň jediný odchozí SMTP port,
  který **Hetzner neblokuje** (viz past v sekci 1) — 465 by z Hetzneru mlčky nefungoval, i
  kdyby se v kódu doplnilo `ssl.enable`.
- SPF na `kvalitacena.cz` už dnes obsahuje Gigaserver (`dig kvalitacena.cz TXT`:
  `v=spf1 mx a include:smtp-gw.gigaserver.cz ~all`) — **žádná změna DNS není potřeba.**

Cena za jednoduchost: horší doručitelnost ze sdílené IP webhostingu a žádné dodací logy jako
u dedikovaného poskytovatele. Přijatelné pro desítky osobně pozvaných testerů, ne pro veřejný
provoz — výměna je naplánovaná do „Před Fází 3" níž.

- [ ] Doplnit do `.env` na serveru:
  ```
  SMTP_HOST=mail.gigaserver.cz
  SMTP_PORT=587
  SMTP_USERNAME=kontakt@kvalitacena.cz
  SMTP_PASSWORD=<heslo schránky, viz sekce 1>
  SMTP_FROM=KvalitaACena <kontakt@kvalitacena.cz>
  ```
- [ ] Doplnit `NOMINATIM_USER_AGENT` do `.env` s reálným kontaktem (`kontakt@kvalitacena.cz`) —
  vyžaduje to usage policy OpenStreetMap Nominatim, jinak riziko zablokování IP serveru.
- [ ] Restartovat appku (`docker compose -f compose.prod.yaml up -d`), ověřit doručení testovacím
  přihlášením na **externí** schránku (Gmail apod., ne `@kvalitacena.cz` — zbytečná komplikace
  při DMARC/loop detekci), a že zpráva skončí v doručené poště, ne ve spamu.

**Past pro budoucí výměnu poskytovatele (Před Fází 3, ne teď):** nový `include:` se musí přidat
do STÁVAJÍCÍHO SPF TXT záznamu, ne vložit jako druhý. Dva SPF záznamy na jednom jménu = trvalá
chyba SPF vyhodnocení = neprojde žádná pošta, včetně OTP a včetně `kontakt@`. DMARC při té
příležitosti nastavit zpočátku na `p=none`, ne rovnou `p=reject`.

**Nouzová berlička, když se ověření domény u SMTP protáhne:** appka bez fungujícího SMTP
nenastartuje ani nepustí dovnitř nikoho (viz úvodní „Pořadí" výš) — `APP_AUTH_OTP_MAIL_ENABLED
=false` v `.env` (zakomentované v `.env.example`) přepne na `ConsoleOtpMailSender`, kód se místo
e-mailu vypíše do logu backendu. Neposílá se klientovi, takže to není bezpečnostní díra, jen
provozní berlička — **vrátit zpátky na `true` (smazat/zakomentovat proměnnou) dřív, než appku
uvidí kdokoli další**, jinak testeři nedostanou kód a budou ho čekat marně.

## 4. Než pozvat první lidi (uzavřená beta, Fáze 2 plánu) — kód hotový, zbývá jen zapnout

`frontend/public/robots.txt` (zákaz indexace) a `backend/.../application-beta.yml` (prahy
důvěry na 0/0/1 pro OSOBNĚ pozvané lidi) jsou v repu hotové. Zbývá:

- [ ] V `.env` na serveru nastavit `SPRING_PROFILES_ACTIVE=prod,beta` (viz `.env.example`) a
  restartovat (`docker compose -f compose.prod.yaml up -d`) — profil `beta` se dá kdykoli
  vypnout zpět na `prod` bez editace kódu.
- [ ] **Přihlásit se poprvé** (vlastním externím e-mailem, JIT registrace při první OTP
  verifikaci) a **označit sebe (a případně dalšího důvěryhodného člověka) jako moderátora** —
  nástroj pro přezkum nahlášených záznamů (`docs/reputace.md`, „Moderace", T4) je hotový, ale
  appka nemá UI na jmenování, jen ruční SQL na serveru. **Past:** `public_handle` v DB je
  jazykově neutrální kanonický klíč (`blue-stork-4271`), zatímco appka v `/moderation` i profilu
  zobrazuje lokalizovanou podobu („Modrý čáp #4271") — opsaný handle z obrazovky do `UPDATE`
  zaktualizuje 0 řádků. Nejdřív zjistit skutečnou hodnotu:
  ```sql
  SELECT id, public_handle, created_at FROM auth.app_user ORDER BY created_at;
  UPDATE auth.app_user SET is_moderator = true WHERE public_handle = '<kanonický handle výš>';
  ```
  Odhlašovat se po `UPDATE` není potřeba — appka čte `is_moderator` z DB přes minutovou cache,
  stačí počkat a stránku obnovit. Bez aspoň jednoho moderátora nemá nahlášený obsah (fotky,
  zboží, obchody) kdo přezkoumat — udělat PŘED pozváním prvních lidí, ne až po prvním nahlášení.
- [ ] **Založit pár obchodů a zboží ve svém okolí** — katalog se záměrně nepředvyplňuje (viz
  níž), ale úplně prázdná appka prvního testera spolehlivě odradí. S profilem `beta` (prahy
  0/0/1) jsou vlastní záznamy vidět hned, bez čekání na potvrzení.
- [ ] **Vyřešit, jak testeři dostanou instalační APK** — appka zatím nemá kde ho nabídnout ke
  stažení (stránka „O aplikaci" na webu žádný odkaz nemá, Caddy servíruje jen statický Angular
  build). Do doby, než je hotové vydání na Play (`docs/vydani.md`), poslat APK přílohou/přes
  úložiště, nebo použít Play internal testing s opt-in odkazem, pokud už existuje.
- **Číselník kategorií zboží byl bez seedu úplně prázdný** — objevilo se to při prvním ručním
  testu 2026-08-19: `CreateProductInput.categoryId` je v GraphQL schématu povinné (`ID!`) a
  žádná `createCategory` mutace neexistuje (kategorie je fixní číselník, ne uživatelský obsah
  jako zboží/obchod), takže appka bez seedu nešla reálně použít — nedalo se založit jediné
  zboží. Opraveno migrací `backend/.../db/changelog/2026-08-19/01-category-seed.yaml` (startovní
  sada 24 kategorií), následně `2026-08-20/01-category-tree.yaml` ji rozšířila na plný strom pro
  běžný supermarket (~106 kategorií, šest kořenů, přeložený do sk/en/pl/de —
  `docs/lokalizace.md`, „Kategorie"). Obě proběhnou automaticky s dalším nasazením, žádný ruční
  krok navíc netřeba. Další rozšíření číselníku zůstává budoucí práce (`docs/rozvoj.md`) —
  hledání a filtr podle kategorie mezitím přibyly (tamtéž).
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
  jen `mailto:kontakt@kvalitacena.cz` na „O aplikaci" (schránka je od 2026-08-22 zřízená,
  sekce 1 výš — pořád nutná jako záložní kanál pro GDPR žádosti, in-app formulář ji nenahrazuje).
  In-app formulář (`core.feedback`, funguje i bez přihlášení, `docs/datovy-model.md`, „Zpětná
  vazba") na webu i Androidu je hotový, fronta pro provozovatele je čtvrtá záložka na
  `/moderation`. Nic dalšího tu nezbývá zapnout.
- [ ] **Před Fází 3** (veřejné spuštění): vrátit `SPRING_PROFILES_ACTIVE` zpět na jen `prod`
  a `frontend/public/robots.txt` buď smazat, nebo povolit indexaci (`Disallow:` prázdné) —
  jinak appka po zveřejnění zůstane neviditelná pro vyhledávače a prahy důvěry 0/0/1 by
  fungovaly i pro veřejnost, ne jen pozvané lidi.
- [ ] **Před Fází 3: vyměnit SMTP Gigaserveru za dedikovaného poskytovatele** (Resend/Postmark/
  SES/Mailgun, sekce 3) — sdílená IP webhostingu stačí na desítky osobně pozvaných testerů, ne
  na veřejný provoz s neznámým objemem a bez kontroly nad doručitelností. Tehdy teprve přijde
  na řadu ověření odesílací domény u nového poskytovatele a sloučení jeho `include:` do
  stávajícího SPF záznamu (past popsaná v sekci 3 výš).
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
docker compose -f compose.prod.yaml exec -T postgres psql -U "$POSTGRES_USER" -d kvalitaacena < dev/beta-report.sql
```

(role `postgres` v produkci neexistuje — `compose.prod.yaml` nastavuje `POSTGRES_USER` z `.env`;
bez `-f compose.prod.yaml` by `docker compose` na serveru sáhl na dev `compose.yaml`, který tam
ani neběží.)

---

Až bude hotovo, tenhle dokument buď smazat, nebo přesunout obsah (co bylo skutečně zvoleno —
poskytovatel, region serveru) do `docs/vydani.md` jako trvalý záznam rozhodnutí, stejně jako
tam je zapsaný postup pro podpisový klíč mobilní appky.
