#!/usr/bin/env bash
# Rychlý start lokálního prostředí pro ruční testování (kompletní ruční postup je
# v docs/spusteni.md). Otevře tři samostatná terminálová okna — databázi, backend
# a frontend — každé v popředí s živými logy, počká až všechny tři naběhnou, otevře
# prohlížeč na frontendu a pak čeká na stisk klávesy. Po stisku korektně ukončí
# všechny tři procesy, včetně zastavení DB kontejneru (data ve volume zůstávají).
#
# Použití:
#   ./start-dev.sh [--no-seed] [--no-open]
#
#   --no-seed   nenahrávat testovací data z dev/seed.sql (jinak se nahrají vždy — vkládají
#               se přes ON CONFLICT DO NOTHING, takže opakované spuštění je bezpečné)
#   --no-open   neotvírat prohlížeč automaticky
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${XDG_RUNTIME_DIR:-/tmp}/kvalitacena-dev"

SEED=1
OPEN_BROWSER=1

usage() {
  echo "Použití: $0 [--no-seed] [--no-open]"
  echo "  --no-seed   nenahrávat testovací data z dev/seed.sql (jinak se nahrají vždy)"
  echo "  --no-open   neotvírat prohlížeč automaticky"
}

for arg in "$@"; do
  case "$arg" in
    --no-seed) SEED=0 ;;
    --no-open) OPEN_BROWSER=0 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Neznámý argument: $arg" >&2
      usage >&2
      exit 1
      ;;
  esac
done

command -v docker >/dev/null || { echo "Chybí docker." >&2; exit 1; }
command -v gnome-terminal >/dev/null || { echo "Chybí gnome-terminal." >&2; exit 1; }

rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"

# --- pomocné funkce ---

# Otevře nové terminálové okno v interaktivním login shellu (aby se načetl ~/.bashrc
# včetně nvm — v neinteraktivním shellu se .bashrc vrací hned na začátku). Okno není
# potomkem tohoto skriptu, takže PID svého shellu si musí samo zapsat do pidfile.
# Po skončení příkazu se okno zavře samo (exit $rc) — při neplánovaném pádu (mimo
# řízené ukončení skriptem, poznané podle souboru .stopping) nejdřív počká na klávesu,
# ať jsou vidět poslední logy.
open_window() {
  local title="$1" pidfile="$2" cmd="$3"
  gnome-terminal --title="$title" -- bash -lic \
    "echo \$\$ > '$pidfile'; $cmd; rc=\$?; \
     [ -f '$RUN_DIR/.stopping' ] || { echo; echo '--- proces skončil (kód '\$rc') ---'; read -n1 -r -s -p 'Stiskni klávesu pro zavření okna...'; }; \
     exit \$rc"
}

# Rekurzivně ukončí proces a všechny jeho potomky — gradlew/npm spouští vnuky
# (java/node), které kill jen na hlavní PID nezasáhne.
kill_tree() {
  local pid="$1"
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child"
  done
  kill -TERM "$pid" 2>/dev/null || true
}

kill_pidfile() {
  local pidfile="$1"
  [ -f "$pidfile" ] || return 0
  kill_tree "$(cat "$pidfile")"
}

# Čeká, až zadaná podmínka (bash příkaz jako string) začne procházet, s tečkovaným
# průběhem. Při vypršení timeoutu vypíše varování a vrátí 1 — volající rozhoduje,
# jestli kvůli tomu skript zastaví, nebo pokračuje dál.
wait_for() {
  local desc="$1" timeout="$2" cond="$3"
  local waited=0
  echo -n "Čekám na $desc"
  until eval "$cond" >/dev/null 2>&1; do
    if [ "$waited" -ge "$timeout" ]; then
      echo " nedoběhlo do ${timeout}s, pokračuji dál (zkontroluj okno s logy)."
      return 1
    fi
    echo -n "."
    sleep 2
    waited=$((waited + 2))
  done
  echo " OK"
}

# Zjistí, jestli už na daném portu na localhostu něco poslouchá — typicky instance
# z dřívějška, kterou tenhle skript nespustil (nemá pro ni pidfile) a tudíž by ji na konci
# ani neuměl zastavit. Bez téhle kontroly by nový gradlew/npm proces na obsazeném portu
# hned spadl, ale wait_for by to nepoznal — port by dál odpovídal (té staré instanci), takže
# by to vypadalo jako úspěšný start.
port_in_use() {
  (echo >"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
}

CLEANED_UP=0
cleanup() {
  [ "$CLEANED_UP" -eq 1 ] && return
  CLEANED_UP=1
  echo
  echo "Ukončuji frontend, backend a databázi..."
  # Napřed signál .stopping, ať se okna po ukončení procesu sama zavřou místo čekání na klávesu.
  touch "$RUN_DIR/.stopping"

  kill_pidfile "$RUN_DIR/frontend.pid"
  kill_pidfile "$RUN_DIR/backend.pid"
  sleep 1
  for pf in "$RUN_DIR/frontend.pid" "$RUN_DIR/backend.pid"; do
    [ -f "$pf" ] && kill -KILL "$(cat "$pf")" 2>/dev/null || true
  done

  (cd "$ROOT_DIR" && docker compose stop) || true
  kill_pidfile "$RUN_DIR/db.pid"

  rm -rf "$RUN_DIR"
  echo "Hotovo."
}
trap cleanup EXIT INT TERM

# --- 1. databáze ---
echo "Spouštím databázi..."
open_window "Kvalita a cena — DB" "$RUN_DIR/db.pid" "cd '$ROOT_DIR' && docker compose up"
wait_for "databázi (port 5437)" 60 \
  "docker compose -f '$ROOT_DIR/compose.yaml' exec -T postgres pg_isready -U postgres -d kvalitaacena" \
  || true

# --- 2. backend ---
if port_in_use 8080; then
  echo "Port 8080 už poslouchá (běží tam něco mimo tento skript) — nový backend nespouštím," \
       "používám ten stávající. Toto okno ho po ukončení skriptem nezastaví."
else
  echo "Spouštím backend (profil beta)..."
  open_window "Kvalita a cena — backend" "$RUN_DIR/backend.pid" \
    "cd '$ROOT_DIR/backend' && SPRING_PROFILES_ACTIVE=beta SPRING_DOCKER_COMPOSE_ENABLED=false ./gradlew bootRun"
fi
wait_for "backend (port 8080)" 240 "curl -sf http://localhost:8080/actuator/health" || true

# --- 2b. seed dat (volitelně, až po Liquibase migraci) ---
if [ "$SEED" -eq 1 ]; then
  echo "Nahrávám testovací data z dev/seed.sql..."
  docker compose -f "$ROOT_DIR/compose.yaml" exec -T postgres \
    psql -U postgres -d kvalitaacena < "$ROOT_DIR/dev/seed.sql"
fi

# --- 3. frontend ---
if port_in_use 4200; then
  echo "Port 4200 už poslouchá (běží tam něco mimo tento skript) — nový frontend nespouštím," \
       "používám ten stávající. Toto okno ho po ukončení skriptem nezastaví."
else
  echo "Spouštím frontend..."
  open_window "Kvalita a cena — frontend" "$RUN_DIR/frontend.pid" \
    "source ~/.nvm/nvm.sh; nvm use 24; cd '$ROOT_DIR/frontend'; [ -d node_modules ] || npm install; npm start"
fi
if wait_for "frontend (port 4200)" 180 "curl -sf http://localhost:4200"; then
  if [ "$OPEN_BROWSER" -eq 1 ]; then
    xdg-open "http://localhost:4200" >/dev/null 2>&1 &
  fi
fi

echo
echo "Běží:"
echo "  web:      http://localhost:4200"
echo "  GraphiQL: http://localhost:8080/graphiql"
echo "  DB:       localhost:5437 (kvalitaacena / postgres)"
echo
read -n1 -r -s -p "Stiskni libovolnou klávesu pro ukončení všech tří procesů..."
