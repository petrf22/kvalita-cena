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
# 0 3 * * * /home/<user>/kvalita-cena/ops/backup.sh >> /var/log/kvalitacena-backup.log 2>&1
```

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
