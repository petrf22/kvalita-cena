#!/usr/bin/env bash
# Rychlý start lokálního prostředí pro ruční testování (kompletní ruční postup je
# v docs/spusteni.md). Otevře samostatná terminálová okna — databázi, backend, frontend,
# Android emulátor a logcat appky — každé v popředí s živými logy, počká až všechny
# naběhnou, nainstaluje a spustí appku v emulátoru, otevře prohlížeč na frontendu a pak
# čeká na stisk klávesy. Po stisku korektně ukončí všechny procesy, včetně zastavení DB
# kontejneru a emulátoru (data ve volume DB zůstávají).
#
# Použití:
#   ./start-dev.sh [--no-seed] [--no-open] [--no-mobile]
#
#   --no-seed    nenahrávat testovací data z dev/seed.sql (jinak se nahrají vždy — vkládají
#                se přes ON CONFLICT DO NOTHING, takže opakované spuštění je bezpečné)
#   --no-open    neotvírat prohlížeč automaticky
#   --no-mobile  nestavět APK a nespouštět Android emulátor
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="${XDG_RUNTIME_DIR:-/tmp}/kvalitacena-dev"

SEED=1
OPEN_BROWSER=1
MOBILE=1

usage() {
  echo "Použití: $0 [--no-seed] [--no-open] [--no-mobile]"
  echo "  --no-seed    nenahrávat testovací data z dev/seed.sql (jinak se nahrají vždy)"
  echo "  --no-open    neotvírat prohlížeč automaticky"
  echo "  --no-mobile  nestavět APK a nespouštět Android emulátor"
}

for arg in "$@"; do
  case "$arg" in
    --no-seed) SEED=0 ;;
    --no-open) OPEN_BROWSER=0 ;;
    --no-mobile) MOBILE=0 ;;
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

# Android SDK cesty (mobile/local.properties, stejný zdroj jako Gradle) — adb/emulator
# nejsou v PATH. Chybějící SDK mobil jen přeskočí, neshodí zbytek skriptu.
SDK_DIR="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/mobile/local.properties" 2>/dev/null | head -1)"
SDK_DIR="${SDK_DIR:-$HOME/Android/Sdk}"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
if [ "$MOBILE" -eq 1 ] && { [ ! -x "$ADB" ] || [ ! -x "$EMULATOR" ]; }; then
  echo "Android SDK (adb/emulator) nenalezeno v $SDK_DIR — mobil přeskakuji" \
       "(oprav mobile/local.properties, nebo spusť s --no-mobile)." >&2
  MOBILE=0
fi

# Název AVD: přednost má proměnná prostředí, jinak první AVD, které emulátor zná — na jiném
# stroji se AVD jmenuje jinak a natvrdo zapsaný název by mobil zbytečně shodil.
if [ "$MOBILE" -eq 1 ]; then
  AVD_NAME="${AVD_NAME:-$("$EMULATOR" -list-avds 2>/dev/null | head -1)}"
  if [ -z "$AVD_NAME" ]; then
    echo "Žádné Android Virtual Device nenalezeno (emulator -list-avds je prázdný) — mobil" \
         "přeskakuji (založ AVD v Android Studiu, nebo spusť s --no-mobile)." >&2
    MOBILE=0
  fi
fi

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

# Rekurzivně ukončí proces a všechny jeho potomky — gradlew/npm spouští vnuky (java/node),
# které signál jen na hlavní PID nezasáhne. Druhý argument je signál (výchozí TERM); volající
# okna jsou interaktivní `bash -lic`, a ten SIGTERM sám o sobě IGNORUJE (ověřeno přes
# /proc/PID/status — interaktivní bash tohle dělá schválně, ať ho nezabije ledajaký TERM),
# takže po TERM vlně vždycky musí přijít druhá vlna s KILL, jinak okno zůstane viset.
kill_tree() {
  local pid="$1" sig="${2:-TERM}"
  [ -n "$pid" ] || return 0
  kill -0 "$pid" 2>/dev/null || return 0
  local child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child" "$sig"
  done
  kill -"$sig" "$pid" 2>/dev/null || true
}

kill_pidfile() {
  local pidfile="$1" sig="${2:-TERM}"
  [ -f "$pidfile" ] || return 0
  kill_tree "$(cat "$pidfile")" "$sig"
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

# Obdoba port_in_use pro emulátor — jakýkoli řádek "emulator-*" v `adb devices` (i ve stavu
# "offline" během bootu) znamená, že už jeden běží. Nový by na stejný AVD stejně nešel spustit
# (zamčený lock soubor), takže radši rovnou použijeme ten stávající.
emulator_running() {
  "$ADB" devices 2>/dev/null | grep -q "^emulator-"
}

# Čeká na dokončení bootu emulátoru — použití: wait_for "..." <timeout> "boot_completed".
boot_completed() {
  [ "$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}

CLEANED_UP=0
cleanup() {
  [ "$CLEANED_UP" -eq 1 ] && return
  CLEANED_UP=1
  echo
  echo "Ukončuji mobil, frontend, backend a databázi..."
  # Napřed signál .stopping, ať se okna po ukončení procesu sama zavřou místo čekání na klávesu.
  touch "$RUN_DIR/.stopping"

  kill_pidfile "$RUN_DIR/logcat.pid"
  # Emulátor spuštěný tímto skriptem vypnout čistě přes adb, ne jen kill oknu — kill by nechal
  # v ~/.android/avd zamčený lock soubor. Pokud skript emulátor nespouštěl (běžel už předtím),
  # emulator.pid neexistuje a tenhle krok je no-op.
  if [ -f "$RUN_DIR/emulator.pid" ] && [ -x "$ADB" ]; then
    "$ADB" -e emu kill >/dev/null 2>&1 || true
  fi
  kill_pidfile "$RUN_DIR/emulator.pid"
  kill_pidfile "$RUN_DIR/frontend.pid"
  kill_pidfile "$RUN_DIR/backend.pid"
  sleep 1
  # Druhá vlna, tvrdě — viz komentář u kill_tree, TERM sám o sobě na interaktivní bash okna
  # nestačí. Volá se znovu přes celý (aktuální, ne zastaralý) strom, ne jen top PID.
  for pf in "$RUN_DIR/logcat.pid" "$RUN_DIR/emulator.pid" "$RUN_DIR/frontend.pid" "$RUN_DIR/backend.pid"; do
    kill_pidfile "$pf" KILL
  done

  (cd "$ROOT_DIR" && docker compose stop) || true
  kill_pidfile "$RUN_DIR/db.pid"
  sleep 1
  kill_pidfile "$RUN_DIR/db.pid" KILL

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

# --- 4. mobil: emulátor + APK + instalace, 5. logcat ---
MOBILE_STARTED=0
if [ "$MOBILE" -eq 1 ]; then
  if emulator_running; then
    echo "Emulátor už běží — nový nespouštím, používám ten stávající. Toto okno ho po" \
         "ukončení skriptem nezastaví."
  else
    echo "Spouštím emulátor ($AVD_NAME)..."
    open_window "Kvalita a cena — emulátor" "$RUN_DIR/emulator.pid" \
      "'$EMULATOR' -avd '$AVD_NAME' -no-snapshot -no-boot-anim -gpu swiftshader_indirect"
  fi

  # Souběžně s bootem emulátoru (běží v jeho vlastním okně) staví hlavní skript APK —
  # bez změn doběhne Gradle z cache jako up-to-date rychle.
  echo "Stavím APK (mobile :app:assembleDebug)..."
  APK_OK=1
  if ! (cd "$ROOT_DIR/mobile" && ./gradlew :app:assembleDebug); then
    echo "Build APK selhal — appku do emulátoru nenainstaluji, zkontroluj chybu výš." >&2
    APK_OK=0
  fi

  "$ADB" wait-for-device
  if wait_for "boot emulátoru" 300 "boot_completed" && [ "$APK_OK" -eq 1 ]; then
    echo "Instaluji a spouštím appku v emulátoru..."
    "$ADB" -e install -r "$ROOT_DIR/mobile/app/build/outputs/apk/debug/app-debug.apk"
    "$ADB" -e shell am start -n cz.kvalitacena/.MainActivity
    MOBILE_STARTED=1

    # Logcat appky ve vlastním okně — smyčka přežije i restart/pád aplikace v emulátoru
    # (pidof appky se dohledá znovu, dokud emulátor běží).
    cat > "$RUN_DIR/logcat-loop.sh" <<EOF
#!/usr/bin/env bash
while true; do
  pid=\$("$ADB" -e shell pidof cz.kvalitacena 2>/dev/null | tr -d '\r')
  [ -n "\$pid" ] && "$ADB" -e logcat --pid="\$pid"
  sleep 2
done
EOF
    chmod +x "$RUN_DIR/logcat-loop.sh"
    open_window "Kvalita a cena — logcat" "$RUN_DIR/logcat.pid" "'$RUN_DIR/logcat-loop.sh'"
  fi
fi

echo
echo "Běží:"
echo "  web:      http://localhost:4200"
echo "  GraphiQL: http://localhost:8080/graphiql"
echo "  DB:       localhost:5437 (kvalitaacena / postgres)"
if [ "$MOBILE_STARTED" -eq 1 ]; then
  echo "  mobil:    cz.kvalitacena v emulátoru $AVD_NAME (volá http://10.0.2.2:8080)"
fi
echo
read -n1 -r -s -p "Stiskni libovolnou klávesu pro ukončení všech procesů..."
