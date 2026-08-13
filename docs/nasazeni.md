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
  neplatné — GDPR žádosti a kontaktní e-mail v podmínkách na něj cílí.
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
- [ ] **Vybrat VPS poskytovatele** — jediná tvrdá podmínka: **perzistentní disk** (fotky leží
  mimo databázi na disku, `docs/datovy-model.md`) — PaaS s efemérním filesystémem (Cloud Run,
  Heroku) nejde použít. Řádově 120–250 Kč/měsíc (viz plán). Kandidáti: Hetzner, DigitalOcean,
  Vultr — cokoliv s KVM/dedikovaným diskem stačí, appka nemá zvláštní HW nároky v etapě 1.
- [ ] **Vybrat cíl pro offsite zálohu** (jiný poskytovatel než VPS výš, ať jeden výpadek
  nevezme obojí) — B2/S3-kompatibilní úložiště s levným cold storage stačí, appka zálohuje jen
  `pg_dump` + adresář `app.media.root`.

## 2. Po výběru VPS

- [ ] Založit server, nainstalovat Docker + Docker Compose.
- [ ] **DNS záznamy** na doméně `kvalitacena.cz` (u registrátora domény, ne u VPS):
  - `A`/`AAAA` `kvalitacena.cz` → IP serveru (web, Caddy)
  - `A`/`AAAA` `api.kvalitacena.cz` → stejná IP (mobil, viz `mobile/app/build.gradle.kts`)
- [ ] Zkopírovat `.env.example` → `.env` na server, doplnit:
  - `POSTGRES_USER`/`POSTGRES_PASSWORD` — libovolné silné heslo, jen pro appku samotnou
  - `JWT_SECRET`/`EMAIL_HASH_PEPPER`/`EMAIL_ENC_KEY` — hodnoty vygenerované v kroku 1 výš
  - `SITE_ADDRESS=https://kvalitacena.cz`
- [ ] `git clone` repa na server, `docker compose -f compose.prod.yaml up -d --build`.
- [ ] Ověřit, že Caddy dostal TLS certifikát (Let's Encrypt, automaticky) — `https://kvalitacena.cz`
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

## 4. Než pozvat první lidi (uzavřená beta, Fáze 2 plánu)

- [ ] `robots.txt` se zákazem indexace, žádná veřejná propagace.
- [ ] Ručně naplnit katalog (obchody + pár set běžných položek jednoho města) — appka nemá
  žádný import OFF/OSM katalogu, `dev/seed.sql` se na produkci **nesmí** pouštět.
- [ ] Dočasně snížit prahy důvěry (`app.trust.*`, `app.catalog.draft-confirmations` v
  `application-prod.yml` nebo přes env) — s hrstkou lidí by se jinak nikdy nesešla potřebná
  3 potvrzení a appka by vypadala prázdná i těm, kdo do ní zapisují.

---

Až bude hotovo, tenhle dokument buď smazat, nebo přesunout obsah (co bylo skutečně zvoleno —
poskytovatel, region serveru) do `docs/vydani.md` jako trvalý záznam rozhodnutí, stejně jako
tam je zapsaný postup pro podpisový klíč mobilní appky.
