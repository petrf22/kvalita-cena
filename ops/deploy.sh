#!/usr/bin/env bash
# Nasazovací skript pro produkci (docs/vydani.md, "Z čeho stavět" — nahrazuje tam popsaný ruční
# postup). Spustit na serveru z checkoutu repa: aktualizuje repo na zadaný tag, sekvenčně sestaví
# backend a web (ne najednou — docs/nasazeni.md, "Sekvenční build", malá instance by se sestavením
# obou naráz vedle Postgresu mohla dostat do OOM), ZAZÁLOHUJE databázi a média, appku nastartuje
# a ověří, že běží na správné verzi. Cesta zpět je ops/rollback.sh (ops/README.md).
#
# Použití:
#   ./ops/deploy.sh <verze>          # např. ./ops/deploy.sh 0.3.0  nebo  ./ops/deploy.sh v0.3.0
#
# Očekává existující tag vX.Y.Z (docs/vydani.md, "Postup vydání" ho vytváří při vydání appky) a
# .env vedle compose.prod.yaml (docs/nasazeni.md, ".env.example").
#
# SKIP_BACKUP=1 vynechá zálohu před nasazením — jen pro případ, že záloha sama nejde udělat
# (plný disk) a nasazení tu chybu opravuje. Bez zálohy nemá rollback.sh z čeho vrátit databázi.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_DIR/compose.prod.yaml"
ENV_FILE="$REPO_DIR/.env"

if [ "$#" -ne 1 ]; then
  echo "Použití: $0 <verze>   (např. $0 0.3.0)" >&2
  exit 1
fi

RAW_VERSION="$1"
VERSION="${RAW_VERSION#v}"
TAG="v$VERSION"

command -v docker >/dev/null || { echo "Chybí docker." >&2; exit 1; }
[ -f "$ENV_FILE" ] || {
  echo "Chybí $ENV_FILE — zkopíruj .env.example a doplň tajemství (docs/nasazeni.md)." >&2
  exit 1
}

cd "$REPO_DIR"

# Načíst .env hned na začátku, ne až před vnějším ověřením — POSTGRES_USER se používá už ve
# wait_for "databázi" krátce po startu, dřívější pozdní source ho tam nechávalo prázdné.
# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a

# --- 1. fáze: přepnout repo na tag a spustit se znovu z něj ---
#
# Tenhle soubor se spouští z checkoutu PŘEDCHOZÍ verze a teprve tady repo přepíná na novou. Bez
# přespuštění by nasazení verze N řídil skript z verze N-1 — oprava nasazovacího postupu by se
# projevila až o jedno vydání později a sourcované knihovny (už z nového tagu) by se s ním
# nemusely shodnout. Po checkoutu se proto skript spustí znovu ze souboru nového tagu;
# DEPLOY_REEXEC brání smyčce a DEPLOY_PREV_VERSION přenáší, co bylo nasazené před přepnutím.
if [ -z "${DEPLOY_REEXEC:-}" ]; then
  # Bezpečnostní pojistka: server checkout nemá mít rozpracované změny (na rozdíl od vývojového
  # stroje se sem nic needituje ručně) — necommitnutá úprava by šla checkoutem tagu tiše ztratit.
  if [ -n "$(git status --porcelain)" ]; then
    echo "Repo v $REPO_DIR má necommitnuté změny — zastavuji se, ať se nic neztratí (git status)." >&2
    exit 1
  fi

  # Co běží teď: stavový soubor z minulého nasazení/návratu, jinak tag, na kterém repo stojí
  # (první nasazení novým skriptem — stavový soubor ještě neexistuje).
  # shellcheck disable=SC1091
  source "$REPO_DIR/ops/lib-deploy.sh" 2>/dev/null || true
  PREV_VERSION=""
  if declare -F state_get >/dev/null; then
    PREV_VERSION="$(state_get CURRENT_VERSION)"
  fi
  if [ -z "$PREV_VERSION" ]; then
    PREV_VERSION="$(git describe --tags --exact-match HEAD 2>/dev/null | sed 's/^v//' || true)"
  fi

  echo "Aktualizuji repository..."
  git fetch origin --tags --prune
  if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "Tag $TAG neexistuje ani po fetch. Dostupné tagy:" >&2
    git tag -l 'v*' --sort=-v:refname | head -10 >&2
    exit 1
  fi
  git -c advice.detachedHead=false checkout "$TAG"

  DEPLOY_REEXEC=1 DEPLOY_PREV_VERSION="$PREV_VERSION" exec "$REPO_DIR/ops/deploy.sh" "$@"
fi

# --- 2. fáze: už běží skript z nasazovaného tagu ---
# shellcheck disable=SC1091
source "$REPO_DIR/ops/lib-deploy.sh"
PREV_VERSION="${DEPLOY_PREV_VERSION:-}"

GIT_SHA="$(git rev-parse --short HEAD)"
export GIT_SHA
echo "Checkout $TAG (commit $GIT_SHA) hotový, dosud běžela verze: ${PREV_VERSION:-neznámá}."

# Běžící image dostanou tag s číslem dosavadní verze, pokud ho ještě nemají. Běžně je to
# zbytečné (otagovalo je nasazení, které je sestavilo), ale při PRVNÍM nasazení tímhle skriptem
# běží image sestavené ještě pod starým jménem bez čísla — bez tohohle kroku by právě první
# nasazení nemělo kam couvnout.
if [ -n "$PREV_VERSION" ] && [ "$PREV_VERSION" != "$VERSION" ]; then
  for pair in "backend:$BACKEND_IMAGE" "web:$WEB_IMAGE"; do
    service="${pair%%:*}"
    image="${pair#*:}"
    if ! image_exists "$image:$PREV_VERSION"; then
      container="$(compose ps -q "$service" 2>/dev/null || true)"
      if [ -n "$container" ]; then
        docker tag "$(docker inspect -f '{{.Image}}' "$container")" "$image:$PREV_VERSION"
        echo "Běžící $service uschován jako $image:$PREV_VERSION."
      fi
    fi
  done
fi

echo "Stavím backend..."
compose build backend
echo "Stavím web..."
compose build web
docker tag "$BACKEND_IMAGE:latest" "$BACKEND_IMAGE:$VERSION"
docker tag "$WEB_IMAGE:latest" "$WEB_IMAGE:$VERSION"

# Build cache Dockeru sám neuklízí — každé nasazení přidá mezivrstvy Gradle a `npm ci`, za necelé
# dva měsíce provozu (2026-10) jí na serveru bylo 21,6 GB proti 1,4 GB images a 74 MB volumes.
# Na 40GB disku by zaplnění jako první odneslo zálohy (ops/backup.sh se pod MIN_FREE_MB záměrně
# nespustí). Týdenní okno nechává vrstvy z nedávných buildů, takže další nasazení zůstane
# rychlé. Selhání úklidu nasazení neshodí — obrazy jsou v tu chvíli už postavené.
echo "Uklízím build cache starší než týden..."
docker builder prune -f --filter until=168h || echo "Pozor: úklid build cache selhal (nasazení pokračuje)." >&2

# Záloha AŽ PO sestavení a těsně před `up -d`: build trvá minuty a appka mezitím dál přijímá
# zápisy, takže dřívější záloha by o ně přišla. Liquibase migrace proběhnou při startu nového
# backendu — tohle je poslední okamžik, kdy databáze ještě odpovídá starému kódu. Když záloha
# selže, nasazení se zastaví (set -e): staré kontejnery běží dál, nic se nezměnilo.
PREDEPLOY_DB=""
PREDEPLOY_MEDIA=""
if [ "${SKIP_BACKUP:-0}" = "1" ]; then
  echo "SKIP_BACKUP=1 — zálohu před nasazením vynechávám, rollback nebude mít z čeho vrátit databázi." >&2
elif [ -z "$(compose ps -a -q postgres 2>/dev/null || true)" ]; then
  echo "Databázový kontejner ještě neexistuje (první instalace) — není co zálohovat."
else
  # Zastavená databáze (server po restartu, ruční `stop`) by zálohu shodila — nejdřív ji zvednout.
  compose up -d postgres
  wait_for "databázi před zálohou" 60 _postgres_ready
  echo "Zálohuji databázi a média před nasazením..."
  "$REPO_DIR/ops/backup.sh"
  # backup.sh pojmenovává soubory vlastním časem; nejnovější dvojice je ta právě vytvořená.
  PREDEPLOY_DB="$(find "$BACKUP_ROOT" -maxdepth 1 -name 'db-*.sql.gz' | sort | tail -n1)"
  PREDEPLOY_MEDIA="$(find "$BACKUP_ROOT" -maxdepth 1 -name 'media-*.tar.gz' | sort | tail -n1)"
fi

# Stav zapsat PŘED `up -d`: když nový backend po migraci nenastartuje, rollback.sh musí vědět,
# kam couvnout a kterou zálohu vzít, i když tenhle skript skončí chybou.
state_write \
  "CURRENT_VERSION=$VERSION" \
  "PREVIOUS_VERSION=$PREV_VERSION" \
  "PREDEPLOY_DB=$PREDEPLOY_DB" \
  "PREDEPLOY_MEDIA=$PREDEPLOY_MEDIA" \
  "DEPLOYED_AT=$(date -Is)"

echo "Spouštím appku..."
compose up -d

FAILED=0
verify_deploy "$VERSION" "$GIT_SHA" || FAILED=1

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Uklízím staré verze image (nechávám $KEEP_IMAGE_VERSIONS nejnovější)..."
  prune_image_versions "$VERSION" "$PREV_VERSION"
  echo "Nasazení $TAG (commit $GIT_SHA) proběhlo a ověření prošlo."
else
  echo "Nasazení $TAG (commit $GIT_SHA) doběhlo, ALE aspoň jedno ověření selhalo — viz výš." >&2
  if [ -n "$PREV_VERSION" ] && [ "$PREV_VERSION" != "$VERSION" ]; then
    echo "Návrat na předchozí verzi:" >&2
    if [ -n "$PREDEPLOY_DB" ]; then
      echo "  ./ops/rollback.sh $PREV_VERSION --restore-db    # i s databází ze zálohy před nasazením" >&2
    fi
    echo "  ./ops/rollback.sh $PREV_VERSION                 # jen kód, databáze zůstane po migraci" >&2
  fi
  exit 1
fi
