#!/usr/bin/env bash
# Zálohovací skript pro produkci (docs/nasazeni.md, "Nastavit cron zálohu"). Zálohuje to samé,
# co appka drží mimo Docker image: databázi (core/agg/auth/off/osm) přes pg_dump a adresář
# médií (fotky, docs/datovy-model.md — leží mimo databázi kvůli perzistentnímu disku). Necílí
# přímo na offsite úložiště — to je záměrně oddělený krok (viz "Nahrání na offsite úložiště"
# níže), ať jeden neúspěšný upload nezastaví ani lokální zálohu.
#
# Spouštět z cronu jako uživatel, který smí `docker compose` (typicky ten, co appku nasadil):
#   0 3 * * * /home/<user>/kvalita-cena/ops/backup.sh >> /var/log/kvalitacena-backup.log 2>&1
#
# Před prvním nasazením do cronu OVĚŘIT OBNOVU na čistou instanci (docs/nasazeni.md) — záloha,
# která se nikdy nezkusila obnovit, není záloha.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/compose.prod.yaml"
ENV_FILE="$REPO_DIR/.env"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/kvalitacena}"
MEDIA_VOLUME="kvalita-a-cena-media-prod"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
TIMESTAMP="$(date +%F-%H%M)"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Chybí $ENV_FILE — bez POSTGRES_USER nejde spustit pg_dump." >&2
  exit 1
fi
# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a

mkdir -p "$BACKUP_ROOT"

echo "[$TIMESTAMP] Zálohuji databázi..."
docker compose -f "$COMPOSE_FILE" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d kvalitaacena \
  | gzip > "$BACKUP_ROOT/db-$TIMESTAMP.sql.gz"

echo "[$TIMESTAMP] Zálohuji média (volume $MEDIA_VOLUME)..."
docker run --rm \
  -v "$MEDIA_VOLUME":/data:ro \
  -v "$BACKUP_ROOT":/backup \
  alpine \
  tar czf "/backup/media-$TIMESTAMP.tar.gz" -C /data .

echo "[$TIMESTAMP] Mažu lokální zálohy starší než $RETENTION_DAYS dní..."
find "$BACKUP_ROOT" -maxdepth 1 -type f \( -name 'db-*.sql.gz' -o -name 'media-*.tar.gz' \) \
  -mtime "+$RETENTION_DAYS" -delete

echo "[$TIMESTAMP] Hotovo: $BACKUP_ROOT/db-$TIMESTAMP.sql.gz, $BACKUP_ROOT/media-$TIMESTAMP.tar.gz"

# --- Nahrání na offsite úložiště ---
# Záměrně bez konkrétního nástroje natvrdo — poskytovatel (docs/nasazeni.md: kandidát
# Backblaze B2) v repu ještě není zvolený/nakonfigurovaný. Po výběru sem doplnit např.:
#   rclone copy "$BACKUP_ROOT/db-$TIMESTAMP.sql.gz" b2:kvalitacena-backup/db/
#   rclone copy "$BACKUP_ROOT/media-$TIMESTAMP.tar.gz" b2:kvalitacena-backup/media/
# a nastavit `rclone config` / proměnné pro přístupové údaje mimo tenhle skript (stejný režim
# jako ostatní tajemství — mimo repo i mimo tenhle stroj do doby, než se použijí v .env).
