#!/usr/bin/env bash
# Návrat na dříve nasazenou verzi (ops/README.md, „rollback.sh"). Nic nestaví: přehodí `:latest`
# na image, které ops/deploy.sh při tehdejším nasazení označil číslem verze, a appku restartuje.
#
# Použití:
#   ./ops/rollback.sh <verze>                         # jen kód; databáze zůstane, jak je
#   ./ops/rollback.sh <verze> --restore-db            # + databáze a média ze zálohy před nasazením
#   ./ops/rollback.sh <verze> --restore-db --backup 2026-11-16-0300   # konkrétní záloha
#   ./ops/rollback.sh --list                          # verze, na které se dá vrátit
#
# Kdy stačí samotný kód a kdy je potřeba --restore-db:
# - Nasazení, které neneslo migraci databáze (nebo jen přidávalo sloupce/tabulky, které starý kód
#   nezná a nevadí mu): stačí kód, o žádná data se nepřijde.
# - Migrace, po které starý kód nenastartuje nebo by psal špatně (přejmenovaný/smazaný sloupec,
#   Hibernate `validate` spadne na neshodě): --restore-db. Tím se ZAHODÍ všechno, co uživatelé
#   zapsali od nasazení — proto se před obnovou udělá ještě jedna záloha aktuálního stavu,
#   ze které jde ztracené zápisy ručně dohledat.
#
# Repo zůstává na tagu novější verze — skript ho záměrně nepřepíná. compose.prod.yaml starého
# tagu nemusí pojmenované image vůbec znát a tenhle skript by přepnutím přepsal sám sebe.
# ops/deploy.sh si příště dosavadní verzi přečte ze stavového souboru, ne z repa.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/compose.prod.yaml"
ENV_FILE="$REPO_DIR/.env"

usage() {
  sed -n '5,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

VERSION=""
RESTORE_DB=0
BACKUP_STAMP=""
ASSUME_YES=0
LIST=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --restore-db) RESTORE_DB=1 ;;
    --backup) shift; BACKUP_STAMP="${1:-}"; [ -n "$BACKUP_STAMP" ] || usage ;;
    --yes) ASSUME_YES=1 ;;
    --list) LIST=1 ;;
    -*) usage ;;
    *) [ -z "$VERSION" ] || usage; VERSION="${1#v}" ;;
  esac
  shift
done

command -v docker >/dev/null || { echo "Chybí docker." >&2; exit 1; }
[ -f "$ENV_FILE" ] || { echo "Chybí $ENV_FILE." >&2; exit 1; }
cd "$REPO_DIR"
# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a
# shellcheck disable=SC1091
source "$REPO_DIR/ops/lib-deploy.sh"
MEDIA_VOLUME="${MEDIA_VOLUME:-kvalita-a-cena-media-prod}"

CURRENT_VERSION="$(state_get CURRENT_VERSION)"

list_versions() {
  echo "Nasazeno teď: ${CURRENT_VERSION:-neznámo}"
  echo "Verze, na které se dá vrátit (image backendu i webu jsou na disku):"
  local version found=0
  while read -r version; do
    [ -n "$version" ] || continue
    image_exists "$WEB_IMAGE:$version" || continue
    echo "  $version"
    found=1
  done < <(image_versions "$BACKEND_IMAGE")
  [ "$found" -eq 1 ] || echo "  (žádná — image s číslem verze vznikají až nasazením přes ops/deploy.sh)"
  local db
  db="$(state_get PREDEPLOY_DB)"
  if [ -n "$db" ]; then
    echo "Záloha před posledním nasazením (pro --restore-db na verzi $(state_get PREVIOUS_VERSION)): $db"
  fi
}

if [ "$LIST" -eq 1 ]; then
  list_versions
  exit 0
fi
[ -n "$VERSION" ] || usage

for image in "$BACKEND_IMAGE" "$WEB_IMAGE"; do
  if ! image_exists "$image:$VERSION"; then
    echo "Image $image:$VERSION na disku není — na tuhle verzi se vrátit nedá." >&2
    list_versions >&2
    exit 1
  fi
done

DB_FILE=""
MEDIA_FILE=""
if [ "$RESTORE_DB" -eq 1 ]; then
  if [ -n "$BACKUP_STAMP" ]; then
    DB_FILE="$BACKUP_ROOT/db-$BACKUP_STAMP.sql.gz"
    MEDIA_FILE="$BACKUP_ROOT/media-$BACKUP_STAMP.tar.gz"
  else
    # Bez --backup jen záloha, kterou deploy.sh udělal těsně před opuštěním právě téhle verze.
    # Jakákoli jiná (starší denní) by k verzi nemusela patřit — tu ať uživatel jmenuje výslovně.
    if [ "$(state_get PREVIOUS_VERSION)" != "$VERSION" ]; then
      echo "Záloha před nasazením ve stavovém souboru patří k verzi" \
           "'$(state_get PREVIOUS_VERSION)', ne k $VERSION." >&2
      echo "Vyber zálohu výslovně: --backup <YYYY-MM-DD-HHMM> (soubory v $BACKUP_ROOT)." >&2
      exit 1
    fi
    DB_FILE="$(state_get PREDEPLOY_DB)"
    MEDIA_FILE="$(state_get PREDEPLOY_MEDIA)"
  fi
  for file in "$DB_FILE" "$MEDIA_FILE"; do
    if [ -z "$file" ] || [ ! -s "$file" ]; then
      echo "Záloha k obnově chybí nebo je prázdná: '${file:-nezaznamenána}'." >&2
      exit 1
    fi
  done
  # Useknutý .gz by se zjistil až uprostřed obnovy, kdy už je databáze smazaná.
  gzip -t "$DB_FILE" "$MEDIA_FILE"
fi

echo "Návrat: ${CURRENT_VERSION:-neznámá verze} → $VERSION"
if [ "$RESTORE_DB" -eq 1 ]; then
  echo "  databáze se SMAŽE a obnoví z: $DB_FILE"
  echo "  média se SMAŽOU a obnoví z:   $MEDIA_FILE"
  echo "  vše zapsané po vzniku té zálohy zmizí (před obnovou udělám ještě jednu zálohu)."
else
  echo "  jen kód — databáze a média zůstávají beze změny."
  echo "  Pokud starší backend nad novějším schématem nenastartuje, je potřeba --restore-db."
fi
if [ "$ASSUME_YES" -ne 1 ]; then
  read -r -p "Pro potvrzení napiš číslo verze ($VERSION): " answer
  [ "$answer" = "$VERSION" ] || { echo "Nepotvrzeno, nic se nezměnilo." >&2; exit 1; }
fi

if [ "$RESTORE_DB" -eq 1 ]; then
  # Backend dolů dřív, než se sáhne na data: nesmí zapisovat do databáze, která se právě maže,
  # ani do složky médií. Web (Caddy) běží dál a vrací 502, dokud backend znovu nenaběhne.
  echo "Zastavuji backend..."
  compose stop backend
  compose up -d postgres
  wait_for "databázi" 60 _postgres_ready

  echo "Zálohuji stav před obnovou (kdyby bylo potřeba dohledat zápisy z mezidobí)..."
  # Bez úklidu starých záloh: retence by mohla smazat právě zálohu, ze které se obnovuje.
  SKIP_RETENTION=1 "$REPO_DIR/ops/backup.sh"
  # Druhá pojistka těsně před DROP — cokoli mezi první kontrolou a teď (souběžný cron backup.sh
  # s retencí) mohlo soubor odnést. Databáze je zatím netknutá, stačí vrátit backend.
  for file in "$DB_FILE" "$MEDIA_FILE"; do
    if [ ! -s "$file" ]; then
      echo "Záloha k obnově mezitím zmizela: '$file' — nic neobnovuji, databáze zůstala beze změny." >&2
      compose up -d backend
      exit 1
    fi
  done

  echo "Obnovuji databázi z $DB_FILE..."
  # WITH (FORCE) odpojí i cizí spojení (DBeaver přes tunel), jinak by DROP čekal donekonečna.
  compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres \
    -c 'DROP DATABASE IF EXISTS kvalitaacena WITH (FORCE)' \
    -c "CREATE DATABASE kvalitaacena OWNER \"$POSTGRES_USER\""
  # ON_ERROR_STOP=1 je nutné — bez něj psql chybu jen vypíše a pokračuje, takže napůl obnovená
  # databáze vypadá jako úspěch (ops/README.md, „Zkouška obnovy").
  zcat "$DB_FILE" \
    | compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d kvalitaacena >/dev/null

  echo "Obnovuji média z $MEDIA_FILE..."
  docker run --rm \
    -v "$MEDIA_VOLUME":/data \
    -v "$(dirname "$MEDIA_FILE")":/backup:ro \
    alpine sh -c "find /data -mindepth 1 -delete && tar xzf '/backup/$(basename "$MEDIA_FILE")' -C /data"
fi

docker tag "$BACKEND_IMAGE:$VERSION" "$BACKEND_IMAGE:latest"
docker tag "$WEB_IMAGE:$VERSION" "$WEB_IMAGE:latest"

# Zálohu před nasazením ze stavu vyřadit: patřila k přechodu, který se právě vrátil, a další
# `--restore-db` bez `--backup` by ji mohl omylem použít pro jiný návrat.
state_write \
  "CURRENT_VERSION=$VERSION" \
  "PREVIOUS_VERSION=$CURRENT_VERSION" \
  "PREDEPLOY_DB=" \
  "PREDEPLOY_MEDIA=" \
  "DEPLOYED_AT=$(date -Is)" \
  "ROLLED_BACK_FROM=$CURRENT_VERSION"

echo "Spouštím appku ve verzi $VERSION..."
compose up -d --no-build

echo
if verify_deploy "$VERSION"; then
  echo "Návrat na verzi $VERSION proběhl a ověření prošlo."
else
  echo "Návrat na verzi $VERSION doběhl, ALE aspoň jedno ověření selhalo — viz výš." >&2
  [ "$RESTORE_DB" -eq 1 ] || echo "Zkus to znovu s --restore-db (starý kód nad novým schématem)." >&2
  exit 1
fi
