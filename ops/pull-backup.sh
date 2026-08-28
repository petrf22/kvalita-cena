#!/usr/bin/env bash
# Stáhne zálohy vytvořené na serveru (ops/backup.sh, docs/nasazeni.md, "Vybrat cíl pro offsite
# zálohu") na lokální stroj do backup/ (gitignored) — mezikrok, dokud appka nemá vlastní offsite
# úložiště. Odsud si uživatel zálohy dál kopíruje ručně na externí disk a NAS.
#
# Spouštět z cronu na LOKÁLNÍM stroji (ne na serveru):
#   0 7 * * * SSH_AUTH_SOCK=/run/user/1000/keyring/ssh /home/<user>/kvalita-cena/ops/pull-backup.sh >> /home/<user>/kvalita-cena/backup/pull.log 2>&1
# SSH_AUTH_SOCK je nutný, pokud je klíč k serveru chráněný heslem a odemčený jen v ssh-agentu
# desktopové session (typicky GNOME/KDE keyring) — cron jinak agenta nezdědí. Funguje to jen
# dokud je uživatel přihlášený a klíčenka odemčená; když je PC vypnuté nebo odhlášené, den
# se prostě přeskočí (další běh doplní i ten den, protože server má vlastní retenci 14 dní).
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_BACKUP_DIR:-$REPO_DIR/backup}"
SERVER_HOST="${SERVER_HOST:-178.105.119.39}"
REMOTE_DIR="${REMOTE_BACKUP_DIR:-/var/backups/kvalitacena}"
TIMESTAMP="$(date +%F-%H%M)"

mkdir -p "$LOCAL_DIR"

echo "[$TIMESTAMP] Stahuji zálohy z $SERVER_HOST:$REMOTE_DIR do $LOCAL_DIR..."
rsync -az --info=stats1 "$SERVER_HOST:$REMOTE_DIR/" "$LOCAL_DIR/"

# Zálohy nesou pg_dump s (šifrovanými) osobními daty — jen pro vlastníka.
chmod 600 "$LOCAL_DIR"/db-*.sql.gz "$LOCAL_DIR"/media-*.tar.gz 2>/dev/null || true

echo "[$TIMESTAMP] Hotovo."
