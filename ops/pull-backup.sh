#!/usr/bin/env bash
# Stáhne zálohy vytvořené na serveru (ops/backup.sh, docs/nasazeni.md, "Vybrat cíl pro offsite
# zálohu") na lokální stroj do backup/ (gitignored) — mezikrok, dokud appka nemá vlastní offsite
# úložiště. Odsud si uživatel zálohy dál kopíruje ručně na externí disk a NAS.
#
# Spouštět z cronu na LOKÁLNÍM stroji (ne na serveru):
#   0 21 * * * /home/<user>/kvalita-cena/ops/pull-backup.sh >> /home/<user>/kvalita-cena/backup/pull.log 2>&1
# Cron nepotřebuje SSH_AUTH_SOCK ani odemčenou klíčenku — použije se vyhrazený klíč BEZ hesla
# (viz BACKUP_SSH_KEY níž, postup zřízení v ops/README.md, "Vyhrazený klíč pro pull-backup.sh").
# Dřívější varianta přes ssh-agent desktopové klíčenky (GNOME/KDE keyring) selhala hned při
# prvním automatickém běhu (2026-08-29): klíčenka klíč neodemkla
# (`sign_and_send_pubkey: ... agent refused operation`), takže celý den zůstal bez lokální
# zálohy a nikdo si toho nevšiml — viz VAROVÁNÍ/notifikace níž a docs/nasazeni.md, sekce 2.
#
# Pokud BACKUP_SSH_KEY neexistuje, skript spadne zpátky na starší chování (klíč z ~/.ssh/config,
# typicky chráněný heslem v ssh-agentu) — funguje jen dokud je uživatel přihlášený a klíčenka
# odemčená; když je PC vypnuté nebo odhlášené, den se přeskočí (další běh doplní i ten den,
# protože server má vlastní retenci 14 dní).
#
# Retence tady je JINÁ než na serveru (viz ops/backup.sh) — schválně GFS (ops/README.md,
# "Retence a úklid"), aby lokál držel dlouhou historii, ne jen posledních pár dní. `rsync` NIŽE
# je záměrně BEZ `--delete` — server smaže po 14 dnech, lokál je archiv; kdyby `--delete` přibylo,
# smazalo by to při každém běhu i historii starší než serverová retence.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_BACKUP_DIR:-$REPO_DIR/backup}"
SERVER_HOST="${SERVER_HOST:-178.105.119.39}"
RETENTION_DAILY_DAYS="${RETENTION_DAILY_DAYS:-30}"
RETENTION_WEEKLY_DAYS="${RETENTION_WEEKLY_DAYS:-180}"
RETENTION_MONTHLY_DAYS="${RETENTION_MONTHLY_DAYS:-730}"
TIMESTAMP="$(date +%F-%H%M)"
BACKUP_SSH_KEY="${BACKUP_SSH_KEY:-$HOME/.ssh/id_ed25519_kvalitacena_backup}"

# shellcheck disable=SC1091
source "$REPO_DIR/ops/lib-retention.sh"

mkdir -p "$LOCAL_DIR"
rotate_log "$LOCAL_DIR/pull.log" 524288 2000

if [[ -f "$BACKUP_SSH_KEY" ]]; then
  # Vyhrazený klíč BEZ hesla, na serveru omezený přes `rrsync -ro /var/backups/kvalitacena`
  # v authorized_keys (`command=...,restrict`) — i kdyby se klíč ztratil, přes něj nejde na
  # serveru spustit nic než čtení té jedné složky. Cesta z pohledu klienta je proto prázdná:
  # rrsync sám doplní skutečnou (chrootovanou) cestu, REMOTE_BACKUP_DIR tu slouží jen jako
  # volitelný podadresář uvnitř toho, co `rrsync` povoluje.
  #
  # `-F /dev/null` je nutné: `~/.ssh/config` má pro tenhle host vlastní `IdentityFile
  # ~/.ssh/id_ed25519` (silnější, neomezený klíč) a ssh identity soubory z configu a z `-i` se
  # SČÍTAJÍ, i s `IdentitiesOnly=yes` — server pak dostal nabídnutý ten neomezený klíč a
  # restrikce se vůbec neuplatnila (ověřeno 2026-08-29, `ssh -v`: „Offering public key:
  # id_ed25519 ... Server accepts key"). Bez configu odpadá i jeho `User kvalitacena`, proto je
  # tu explicitní REMOTE_USER.
  REMOTE_DIR="${REMOTE_BACKUP_DIR:-}"
  REMOTE_USER="${REMOTE_BACKUP_USER:-kvalitacena}"
  SERVER_TARGET="$REMOTE_USER@$SERVER_HOST"
  RSYNC_SSH=(ssh -F /dev/null -i "$BACKUP_SSH_KEY" -o IdentitiesOnly=yes -o BatchMode=yes)
else
  # Fallback: klíč podle ~/.ssh/config (typicky chráněný heslem v ssh-agentu/klíčence).
  REMOTE_DIR="${REMOTE_BACKUP_DIR:-/var/backups/kvalitacena}"
  SERVER_TARGET="$SERVER_HOST"
  RSYNC_SSH=(ssh -o BatchMode=yes)
fi
if [[ -n "$REMOTE_DIR" ]]; then
  REMOTE_SRC="$SERVER_TARGET:$REMOTE_DIR/"
else
  REMOTE_SRC="$SERVER_TARGET:"
fi

# Desktopová notifikace — jen při problému, nikdy při úspěchu. Cron nedědí
# DBUS_SESSION_BUS_ADDRESS, proto se dopočítává z UID; bez notify-send (např. jiný stroj) se
# jen tiše přeskočí, log zůstává jediným záznamem.
notify() {
  command -v notify-send >/dev/null 2>&1 || return 0
  DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=/run/user/$(id -u)/bus}" \
    notify-send -u critical "Záloha kvalita-cena" "$1" 2>/dev/null || true
}

# Kontrola čerstvosti + selhání běhu — na `trap ... EXIT`, ať doběhne i když `rsync`/cokoli
# jiného spadne pod `set -e`. Dřív byla na konci skriptu za GFS ořezáním, takže při chybě výš
# (přesně to, co se stalo 2026-08-29 — selhání přihlášení) se vůbec nespustila a incident zůstal
# viditelný jen jako řádek v pull.log, který nikdo nečte.
final_checks() {
  local exit_code=$?
  set +e

  if (( exit_code != 0 )); then
    echo "[$TIMESTAMP] SELHALO (exit $exit_code) — zálohy se nemusely stáhnout, viz chyba výš." >&2
    notify "Stahování selhalo (exit $exit_code) — zkontroluj $LOCAL_DIR/pull.log"
  fi

  local db_timestamps=() f ts newest_db newest_epoch age_days
  for f in "$LOCAL_DIR"/db-*.sql.gz; do
    [[ -e "$f" ]] || continue
    ts="$(basename "$f" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}')"
    [[ -n "$ts" ]] && db_timestamps+=("$ts")
  done
  if (( ${#db_timestamps[@]} == 0 )); then
    echo "[$TIMESTAMP] VAROVÁNÍ: v $LOCAL_DIR není žádná stažená záloha DB." >&2
    notify "V $LOCAL_DIR není žádná stažená záloha DB."
  else
    newest_db="$(printf '%s\n' "${db_timestamps[@]}" | sort | tail -n1)"
    newest_epoch="$(date -d "${newest_db:0:10} ${newest_db:11:2}:${newest_db:13:2}" +%s)"
    age_days=$(( ($(date +%s) - newest_epoch) / 86400 ))
    if (( age_days > 2 )); then
      echo "[$TIMESTAMP] VAROVÁNÍ: nejnovější stažená záloha DB je stará $age_days dní (poslední: $newest_db) — zkontroluj cron na serveru." >&2
      notify "Nejnovější stažená záloha DB je stará $age_days dní (poslední: $newest_db)."
    fi
  fi

  exit "$exit_code"
}
trap final_checks EXIT

echo "[$TIMESTAMP] Stahuji zálohy z $REMOTE_SRC do $LOCAL_DIR..."
# `-e` bere jeden string, který rsync sám dál dělí na slova mezerou (ne plnohodnotný shell
# parsing) — bezpečné tu, protože žádná složka RSYNC_SSH mezeru neobsahuje (cesta ke klíči je
# vždy pod $HOME/.ssh bez mezer).
rsync -az --info=stats1 -e "${RSYNC_SSH[*]}" "$REMOTE_SRC" "$LOCAL_DIR/"

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

echo "[$TIMESTAMP] Hotovo."
