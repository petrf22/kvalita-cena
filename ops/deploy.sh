#!/usr/bin/env bash
# Nasazovací skript pro produkci (docs/vydani.md, "Z čeho stavět" — nahrazuje tam popsaný ruční
# postup). Spustit na serveru z checkoutu repa: aktualizuje repo na zadaný tag, sekvenčně sestaví
# backend a web (ne najednou — docs/nasazeni.md, "Sekvenční build", malá instance by se sestavením
# obou naráz vedle Postgresu mohla dostat do OOM), appku nastartuje a ověří, že běží na správné
# verzi.
#
# Použití:
#   ./ops/deploy.sh <verze>          # např. ./ops/deploy.sh 0.3.0  nebo  ./ops/deploy.sh v0.3.0
#
# Očekává existující tag vX.Y.Z (docs/vydani.md, "Postup vydání" ho vytváří při vydání appky) a
# .env vedle compose.prod.yaml (docs/nasazeni.md, ".env.example").
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

# Bezpečnostní pojistka: server checkout nemá mít rozpracované změny (na rozdíl od vývojového
# stroje se sem nic needituje ručně) — necommitnutá úprava by šla checkoutem tagu tiše ztratit.
if [ -n "$(git status --porcelain)" ]; then
  echo "Repo v $REPO_DIR má necommitnuté změny — zastavuji se, ať se nic neztratí (git status)." >&2
  exit 1
fi

echo "Aktualizuji repository..."
git fetch origin --tags --prune
if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Tag $TAG neexistuje ani po fetch. Dostupné tagy:" >&2
  git tag -l 'v*' --sort=-v:refname | head -10 >&2
  exit 1
fi
git -c advice.detachedHead=false checkout "$TAG"

GIT_SHA="$(git rev-parse --short HEAD)"
export GIT_SHA
echo "Checkout $TAG (commit $GIT_SHA) hotový."

# --- pomocná funkce (stejný vzor jako start-dev.sh) ---
wait_for() {
  local desc="$1" timeout="$2" cond="$3"
  local waited=0
  echo -n "Čekám na $desc"
  until eval "$cond" >/dev/null 2>&1; do
    if [ "$waited" -ge "$timeout" ]; then
      echo " nedoběhlo do ${timeout}s."
      return 1
    fi
    echo -n "."
    sleep 3
    waited=$((waited + 3))
  done
  echo " OK"
}

echo "Stavím backend..."
docker compose -f "$COMPOSE_FILE" build backend
echo "Stavím web..."
docker compose -f "$COMPOSE_FILE" build web

echo "Spouštím appku..."
docker compose -f "$COMPOSE_FILE" up -d

# --- ověření ---
FAILED=0

wait_for "databázi" 60 \
  "docker compose -f '$COMPOSE_FILE' exec -T postgres pg_isready -U \"\$POSTGRES_USER\" -d kvalitaacena" \
  || FAILED=1

wait_for "start backendu (Spring Boot log)" 180 \
  "docker compose -f '$COMPOSE_FILE' logs backend 2>&1 | grep -qE 'Started .* in [0-9.]+ seconds'" \
  || FAILED=1

if docker compose -f "$COMPOSE_FILE" logs backend 2>&1 | grep -qiE 'ERROR|Exception'; then
  echo "Pozor: v logu backendu je ERROR/Exception — zkontroluj ručně (nezastavuje ověření):"
  docker compose -f "$COMPOSE_FILE" logs backend 2>&1 | grep -iE 'ERROR|Exception' | tail -10
fi

echo "Stav kontejnerů:"
docker compose -f "$COMPOSE_FILE" ps

# Načíst SITE_ADDRESS/API_ADDRESS z .env, ať jde ověřit i zvenčí — než je DNS/doména nastavená
# (docs/nasazeni.md, "Doporučené pořadí prací"), zůstávají na výchozích *.localhost hodnotách
# a appka je zvenčí nedostupná; v tom případě vnější kontroly jen přeskočíme s poznámkou.
# shellcheck disable=SC1090
set -a && source "$ENV_FILE" && set +a
SITE_ADDRESS="${SITE_ADDRESS:-http://localhost}"
API_ADDRESS="${API_ADDRESS:-http://api.localhost}"

if [[ "$SITE_ADDRESS" == *localhost* ]]; then
  echo "SITE_ADDRESS ($SITE_ADDRESS) je pořád na výchozí hodnotě — vnější ověření webu přeskakuji" \
       "(docs/nasazeni.md, dřív než se přepne DNS ověřuj proti IP ručně: curl http://<IP>/)."
else
  echo "Ověřuji web na $SITE_ADDRESS..."
  if curl -sf -o /dev/null "$SITE_ADDRESS"; then
    echo "  web OK"
  else
    echo "  web NEODPOVÍDÁ na $SITE_ADDRESS" >&2
    FAILED=1
  fi
fi

if [[ "$API_ADDRESS" == *localhost* ]]; then
  echo "API_ADDRESS ($API_ADDRESS) je pořád na výchozí hodnotě — vnější ověření backendu" \
       "přeskakuji."
else
  echo "Ověřuji backend na $API_ADDRESS..."
  HEALTH="$(curl -sf "$API_ADDRESS/actuator/health" || true)"
  if [[ "$HEALTH" == *'"status":"UP"'* ]]; then
    echo "  health OK ($HEALTH)"
  else
    echo "  health CHYBA: '$HEALTH'" >&2
    FAILED=1
  fi

  # /actuator/info vrací {"build":{"version":"...","commit":"...", ...}} — "commit" je vlastní
  # additional property z backend/build.gradle (buildInfo), plochý string, ne vnořený objekt.
  INFO="$(curl -sf "$API_ADDRESS/actuator/info" || true)"
  DEPLOYED_VERSION="$(echo "$INFO" | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)"
  DEPLOYED_COMMIT="$(echo "$INFO" | grep -o '"commit":"[^"]*"' | head -1 | cut -d'"' -f4)"

  if [ "$DEPLOYED_VERSION" = "$VERSION" ]; then
    echo "  verze OK ($DEPLOYED_VERSION)"
  else
    echo "  verze NESEDÍ: očekáváno $VERSION, appka hlásí '$DEPLOYED_VERSION' ($INFO)" >&2
    FAILED=1
  fi

  if [ -n "$DEPLOYED_COMMIT" ] && [[ "$DEPLOYED_COMMIT" == "$GIT_SHA"* ]]; then
    echo "  commit OK ($DEPLOYED_COMMIT)"
  else
    echo "  commit se neshoduje nebo chybí: očekáváno $GIT_SHA, appka hlásí '$DEPLOYED_COMMIT'" >&2
    FAILED=1
  fi
fi

echo
if [ "$FAILED" -eq 0 ]; then
  echo "Nasazení $TAG (commit $GIT_SHA) proběhlo a ověření prošlo."
else
  echo "Nasazení $TAG (commit $GIT_SHA) doběhlo, ALE aspoň jedno ověření selhalo — viz výš." >&2
  exit 1
fi
