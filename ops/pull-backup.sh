#!/usr/bin/env bash
# Stáhne zálohy vytvořené na serveru (ops/backup.sh, docs/nasazeni.md, "Vybrat cíl pro offsite
# zálohu") na lokální stroj do backup/ (gitignored) — mezikrok, dokud appka nemá vlastní offsite
# úložiště. Odsud si uživatel zálohy dál kopíruje ručně na externí disk a NAS.
#
# Spouštět z cronu na LOKÁLNÍM stroji (ne na serveru):
#   0 21 * * * SSH_AUTH_SOCK=/run/user/1000/keyring/ssh /home/<user>/kvalita-cena/ops/pull-backup.sh >> /home/<user>/kvalita-cena/backup/pull.log 2>&1
# SSH_AUTH_SOCK je nutný, pokud je klíč k serveru chráněný heslem a odemčený jen v ssh-agentu
# desktopové session (typicky GNOME/KDE keyring) — cron jinak agenta nezdědí. Funguje to jen
# dokud je uživatel přihlášený a klíčenka odemčená; když je PC vypnuté nebo odhlášené, den
# se prostě přeskočí (další běh doplní i ten den, protože server má vlastní retenci 14 dní).
#
# Retence tady je JINÁ než na serveru (viz ops/backup.sh) — schválně GFS (ops/README.md,
# "Retence a úklid"), aby lokál držel dlouhou historii, ne jen posledních pár dní. `rsync` NIŽE
# je záměrně BEZ `--delete` — server smaže po 14 dnech, lokál je archiv; kdyby `--delete` přibylo,
# smazalo by to při každém běhu i historii starší než serverová retence.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_BACKUP_DIR:-$REPO_DIR/backup}"
SERVER_HOST="${SERVER_HOST:-178.105.119.39}"
REMOTE_DIR="${REMOTE_BACKUP_DIR:-/var/backups/kvalitacena}"
RETENTION_DAILY_DAYS="${RETENTION_DAILY_DAYS:-30}"
RETENTION_WEEKLY_DAYS="${RETENTION_WEEKLY_DAYS:-180}"
RETENTION_MONTHLY_DAYS="${RETENTION_MONTHLY_DAYS:-730}"
TIMESTAMP="$(date +%F-%H%M)"

# shellcheck disable=SC1091
source "$REPO_DIR/ops/lib-retention.sh"

mkdir -p "$LOCAL_DIR"
rotate_log "$LOCAL_DIR/pull.log" 524288 2000

echo "[$TIMESTAMP] Stahuji zálohy z $SERVER_HOST:$REMOTE_DIR do $LOCAL_DIR..."
rsync -az --info=stats1 "$SERVER_HOST:$REMOTE_DIR/" "$LOCAL_DIR/"

# Zálohy nesou pg_dump s (šifrovanými) osobními daty — jen pro vlastníka.
chmod 600 "$LOCAL_DIR"/db-*.sql.gz "$LOCAL_DIR"/media-*.tar.gz 2>/dev/null || true

# GFS retence — spočítat, které timestampy zůstávají, PODLE db-* záloh a stejnou množinu
# uplatnit i na media-*, ať dvojice zůstanou svázané (nikdy nezbyde db- bez odpovídajícího
# media- nebo naopak).
echo "[$TIMESTAMP] Ořezávám lokální archiv (GFS: denní $RETENTION_DAILY_DAYS dní / týdenní $RETENTION_WEEKLY_DAYS dní / měsíční $RETENTION_MONTHLY_DAYS dní)..."
db_timestamps=()
for f in "$LOCAL_DIR"/db-*.sql.gz; do
  [[ -e "$f" ]] || continue
  ts="$(basename "$f" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}')"
  [[ -n "$ts" ]] && db_timestamps+=("$ts")
done
keep_list="$(retention_keep_set "$RETENTION_DAILY_DAYS" "$RETENTION_WEEKLY_DAYS" "$RETENTION_MONTHLY_DAYS" "${db_timestamps[@]}")"
retention_prune_dir "$LOCAL_DIR" 'db-*.sql.gz' "$keep_list"
retention_prune_dir "$LOCAL_DIR" 'media-*.tar.gz' "$keep_list"

# Kontrola čerstvosti — bez lokálního MTA se chybová pošta cronu jen tiše ztratí (přesně tohle
# se stalo 2026-08-28 na serveru, 5 dní bez zálohy si nikdo nevšiml). Log to nezachrání sám o
# sobě, ale aspoň je vidět na první pohled, i bez počítání dnů podle jmen souborů.
newest_db="$(printf '%s\n' "${db_timestamps[@]}" | sort | tail -n1)"
if [[ -n "$newest_db" ]]; then
  newest_epoch="$(date -d "${newest_db:0:10} ${newest_db:11:2}:${newest_db:13:2}" +%s)"
  age_days=$(( ($(date +%s) - newest_epoch) / 86400 ))
  if (( age_days > 2 )); then
    echo "[$TIMESTAMP] VAROVÁNÍ: nejnovější stažená záloha DB je stará $age_days dní (poslední: $newest_db) — zkontroluj cron na serveru." >&2
  fi
fi

echo "[$TIMESTAMP] Hotovo."
