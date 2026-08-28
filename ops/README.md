# Provozní skripty

Praktický protějšek k `docs/nasazeni.md` (checklist) — tady je to, co se má skutečně spustit.

## `deploy.sh`

Nasazení vydané verze na server: `./ops/deploy.sh <verze>` (např. `./ops/deploy.sh 0.3.0`).
Aktualizuje repo na tag `vX.Y.Z` (musí už existovat — vzniká při vydání, `docs/vydani.md`,
„Postup vydání"), sekvenčně sestaví backend a web (`docs/nasazeni.md`, „Sekvenční build" — ne
najednou, na malé instanci riziko OOM), appku spustí a ověří: kontejnery běží, backend nastartoval
bez chyby v logu, a pokud je v `.env` už nastavená skutečná doména (ne výchozí `*.localhost`),
i zvenčí — `/actuator/health` je `UP` a `/actuator/info` hlásí přesně tuhle verzi a commit.
Zastaví se (bez checkoutu) na necommitnutých změnách v repu na serveru — tam se ručně needituje,
takže by to byla rozdělaná práce, ne úmyslný stav.

Spustit z checkoutu repa na serveru:

```bash
./ops/deploy.sh 0.3.0
```

## `backup.sh`

Zálohuje databázi (`pg_dump`) a adresář médií (Docker volume `kvalita-a-cena-media-prod`) do
`/var/backups/kvalitacena` (přepiš proměnnou `BACKUP_ROOT`, pokud má být jinde). Nahrání na
offsite úložiště (Backblaze B2 nebo jiné — v `docs/nasazeni.md` zatím nerozhodnuto) je v skriptu
jen jako komentovaný návod na konci, ne hotová funkce — doplnit až po výběru poskytovatele.

Cron (na serveru, jako uživatel s právem na `docker compose`):

```bash
crontab -e
# 0 3 * * * /home/<user>/kvalita-cena/ops/backup.sh >> /var/backups/kvalitacena/backup.log 2>&1
```

**Log nikdy do `/var/log/`** — ten patří `root:syslog`, běžný uživatel do něj nemůže zapisovat.
Přesměrování tam pak selže dřív, než se skript vůbec spustí, a cron to nijak nenahlásí (bez
lokálního MTA se chybová pošta cronu jen tiše ztratí) — v produkci to takhle 5 dní běželo bez
jediné automatické zálohy, než si toho někdo všiml (2026-08-28). Log místo toho patří do
`BACKUP_ROOT` výš, kam skript sám zapisuje zálohy, takže tam zapisovat může i cron.

## `pull-backup.sh`

Stáhne zálohy ze serveru (výš) na lokální stroj do `backup/` (gitignored) — mezikrok, dokud appka
nemá vlastní offsite úložiště (`docs/nasazeni.md`, „Vybrat cíl pro offsite zálohu"). Odsud si
uživatel zálohy dál kopíruje ručně na externí disk a NAS.

Cron na LOKÁLNÍM stroji (ne na serveru):

```bash
crontab -e
# 0 21 * * * SSH_AUTH_SOCK=/run/user/1000/keyring/ssh /home/<user>/kvalita-cena/ops/pull-backup.sh >> /home/<user>/kvalita-cena/backup/pull.log 2>&1
```

Čas voleno večer, ne ráno po serverové záloze (3:00 UTC) — u desktopu je běžnější, že bývá
zapnutý večer, ne brzy ráno.

`SSH_AUTH_SOCK` je potřeba, pokud je klíč k serveru chráněný heslem a odemčený jen v ssh-agentu
desktopové session (GNOME/KDE keyring) — cron by ho jinak nezdědil a `rsync` by tiše selhal na
přihlášení. Funguje to jen dokud je uživatel přihlášený a klíčenka odemčená; když je PC vypnuté,
den se přeskočí — další běh ho doplní, protože server má vlastní retenci 14 dní
(`ops/backup.sh`, `RETENTION_DAYS`).

## Zkouška obnovy — udělat PŘED zapnutím cronu, ne až při skutečné nehodě

`docs/nasazeni.md` to vyžaduje explicitně: „vyzkoušet obnovu na čistou instanci, ne jen že záloha
proběhla bez chyby." Nejjednodušší způsob je druhá, dočasná Hetzner instance (u hodinové
fakturace stojí zkouška pár korun):

1. Na čisté instanci: `git clone`, `cp .env.example .env`, doplnit `POSTGRES_USER`/
   `POSTGRES_PASSWORD` (nemusí sedět s produkčními — je to jen dočasná instance) a stejná
   tajemství jako na produkci (jinak by po obnově nešlo dekódovat uložené e-maily).
2. `docker compose -f compose.prod.yaml up -d postgres` — jen databáze, počkat, až naběhne.
3. Obnovit databázi ze zálohy:
   ```bash
   zcat db-<timestamp>.sql.gz | \
     docker compose -f compose.prod.yaml exec -T postgres psql -U "$POSTGRES_USER" -d kvalitaacena
   ```
4. Obnovit média do volume (appka musí být předtím aspoň jednou nastartovaná, ať volume existuje):
   ```bash
   docker run --rm \
     -v kvalita-a-cena-media-prod:/data \
     -v "$(pwd)":/backup \
     alpine sh -c "cd /data && tar xzf /backup/media-<timestamp>.tar.gz"
   ```
5. `docker compose -f compose.prod.yaml up -d`, ověřit v UI, že produkty/ceny/fotky ze zálohy
   skutečně existují (ne jen že appka naběhla bez chyby).
6. Smazat dočasnou instanci.

## Nouzový přístup bez fungujícího SMTP

Viz komentář u `APP_AUTH_OTP_MAIL_ENABLED` v `.env.example` — dočasně obchází to, že appka bez
SMTP nepustí dovnitř nikoho. Použít jen do doby, než SMTP poskytovatel doopravdy doručuje, a
zapnout zpátky (smazat/zakomentovat proměnnou) před pozváním dalších lidí.
