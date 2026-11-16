#!/usr/bin/env bash
# Sdílené funkce pro nasazení a návrat (ops/deploy.sh, ops/rollback.sh) a obal nad
# `docker compose`, který používá i ops/backup.sh. Sourcuje se, nespouští — proto bez `set -e`.
# Volající musí mít nastavené REPO_DIR a COMPOSE_FILE.
#
# Jak drží cesta zpět pohromadě (ops/README.md, „rollback.sh"):
# - compose.prod.yaml pojmenovává image `kvalitacena-backend` / `kvalitacena-web` bez tagu, tedy
#   `:latest`. Holé `docker compose -f compose.prod.yaml up -d` kdekoli v dokumentaci proto vždy
#   spustí to, co je právě nasazené — žádná proměnná s verzí, na kterou by šlo zapomenout.
# - deploy.sh po sestavení přidá týmž obrazům tag s číslem verze (`:0.13.6`). To je archiv:
#   rollback.sh jen přehodí `:latest` zpět na starší číslo a pustí `up -d --no-build`.
# - Co bylo nasazené předtím a která záloha vznikla těsně před migrací, si pamatuje stavový
#   soubor v BACKUP_ROOT (DEPLOY_STATE_FILE) — ne repo, tam by neverzovaný soubor zastavil
#   deploy.sh na kontrole čistého pracovního stromu.

BACKEND_IMAGE="kvalitacena-backend"
WEB_IMAGE="kvalitacena-web"
BACKUP_ROOT="${BACKUP_ROOT:-/var/backups/kvalitacena}"
DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE:-$BACKUP_ROOT/deploy-state.env}"
# Kolik očíslovaných verzí image nechat na disku (aktuální a předchozí zůstávají vždy).
KEEP_IMAGE_VERSIONS="${KEEP_IMAGE_VERSIONS:-3}"

# COMPOSE_OVERRIDE_FILE = nepovinný druhý compose soubor přiložený ke každému volání — úpravy
# platné jen pro jeden stroj (jiné porty a jména volumes při zkoušce nasazení na vývojovém PC,
# viz ops/README.md, „Zkouška nasazení nanečisto"). Na produkci se nenastavuje.
compose() {
  local files=(-f "$COMPOSE_FILE")
  [[ -n "${COMPOSE_OVERRIDE_FILE:-}" ]] && files+=(-f "$COMPOSE_OVERRIDE_FILE")
  docker compose "${files[@]}" "$@"
}

# wait_for <popis> <timeout_s> <příkaz...> — opakuje příkaz po 3 s, dokud neprojde.
wait_for() {
  local desc="$1" timeout="$2" waited=0
  shift 2
  echo -n "Čekám na $desc"
  until "$@" >/dev/null 2>&1; do
    if (( waited >= timeout )); then
      echo " nedoběhlo do ${timeout}s."
      return 1
    fi
    echo -n "."
    sleep 3
    waited=$((waited + 3))
  done
  echo " OK"
}

_postgres_ready() {
  compose exec -T postgres pg_isready -U "$POSTGRES_USER" -d kvalitaacena
}

_backend_started() {
  compose logs backend 2>&1 | grep -qE 'Started .* in [0-9.]+ seconds'
}

# state_get <KLÍČ> — hodnota ze stavového souboru, prázdný řetězec, když soubor nebo klíč chybí.
state_get() {
  [[ -f "$DEPLOY_STATE_FILE" ]] || return 0
  sed -n "s/^$1=//p" "$DEPLOY_STATE_FILE" | tail -n1
}

# state_write KLÍČ=hodnota... — přepíše stavový soubor celý (přes dočasný soubor, ať po pádu
# uprostřed zápisu nezůstane useknutý).
state_write() {
  mkdir -p "$(dirname "$DEPLOY_STATE_FILE")"
  local tmp
  tmp="$(mktemp "$DEPLOY_STATE_FILE.XXXXXX")"
  printf '%s\n' "$@" > "$tmp"
  mv "$tmp" "$DEPLOY_STATE_FILE"
}

image_exists() {
  docker image inspect "$1" >/dev/null 2>&1
}

# Očíslované verze jednoho image, nejnovější první (bez `latest`).
image_versions() {
  docker image ls "$1" --format '{{.Tag}}' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | sort -rV
}

# prune_image_versions <ponechat...> — smaže očíslované tagy nad KEEP_IMAGE_VERSIONS nejnovějších;
# vyjmenované verze (aktuální, předchozí) zůstávají vždy. `docker rmi` na tag jen odebere
# jmenovku — vrstvy sdílené s jiným tagem (typicky `:latest`) zůstanou.
prune_image_versions() {
  local image version kept
  for image in "$BACKEND_IMAGE" "$WEB_IMAGE"; do
    kept=0
    while read -r version; do
      [[ -n "$version" ]] || continue
      kept=$((kept + 1))
      (( kept <= KEEP_IMAGE_VERSIONS )) && continue
      [[ " $* " == *" $version "* ]] && continue
      echo "  mažu starý image $image:$version"
      docker rmi "$image:$version" >/dev/null || true
    done < <(image_versions "$image")
  done
}

# verify_deploy <verze> [commit] — ověří běžící appku; vrací 1, když aspoň jedna kontrola selže.
# Commit je nepovinný: rollback ho nezná (repo zůstává na novějším tagu), kontroluje jen verzi.
verify_deploy() {
  local expected_version="$1" expected_commit="${2:-}" failed=0
  local site_address="${SITE_ADDRESS:-http://localhost}" api_address="${API_ADDRESS:-http://api.localhost}"

  wait_for "databázi" 60 _postgres_ready || failed=1
  wait_for "start backendu (Spring Boot log)" 180 _backend_started || failed=1

  if compose logs backend 2>&1 | grep -qiE 'ERROR|Exception'; then
    echo "Pozor: v logu backendu je ERROR/Exception — zkontroluj ručně (nezastavuje ověření):"
    compose logs backend 2>&1 | grep -iE 'ERROR|Exception' | tail -10
  fi

  echo "Stav kontejnerů:"
  compose ps

  # Než je DNS/doména nastavená (docs/nasazeni.md, "Doporučené pořadí prací"), zůstávají adresy
  # na výchozích *.localhost hodnotách a appka je zvenčí nedostupná — vnější kontroly se pak
  # jen přeskočí s poznámkou.
  if [[ "$site_address" == *localhost* ]]; then
    echo "SITE_ADDRESS ($site_address) je pořád na výchozí hodnotě — vnější ověření webu přeskakuji" \
         "(docs/nasazeni.md, dřív než se přepne DNS ověřuj proti IP ručně: curl http://<IP>/)."
  else
    echo "Ověřuji web na $site_address..."
    # Caddy po prvním startu (nový server, smazaná caddy-data) teprve žádá Let's Encrypt
    # o certifikát — 2026-10-28 při přechodu na nový server ho měl až ~7 s po startu kontejneru
    # a jediný pokus hned po `up -d` hlásil falešné selhání. Stejné opakování jako u health níž.
    local i web_ok=0
    for ((i = 1; i <= 5; i++)); do
      curl -sf -o /dev/null "$site_address" && { web_ok=1; break; }
      (( i < 5 )) && sleep 5
    done
    if (( web_ok == 1 )); then
      echo "  web OK"
    else
      echo "  web NEODPOVÍDÁ na $site_address ani po 5 pokusech" >&2
      failed=1
    fi
  fi

  if [[ "$api_address" == *localhost* ]]; then
    echo "API_ADDRESS ($api_address) je pořád na výchozí hodnotě — vnější ověření backendu" \
         "přeskakuji."
  else
    echo "Ověřuji backend na $api_address..."
    # management.health.mail.enabled testuje SMTP spojení při každém volání /actuator/health —
    # těsně po restartu kontejneru umí být tenhle test na první pokus DOWN (503, curl -f tělo
    # zahodí), i když appka i SMTP jsou v pořádku. Pár opakování s prodlevou tomu dá šanci se
    # ustálit, než to nahlásíme jako selhané nasazení.
    local health="" info deployed_version deployed_commit
    for ((i = 1; i <= 5; i++)); do
      health="$(curl -sf "$api_address/actuator/health" || true)"
      [[ "$health" == *'"status":"UP"'* ]] && break
      (( i < 5 )) && sleep 5
    done
    if [[ "$health" == *'"status":"UP"'* ]]; then
      echo "  health OK ($health)"
    else
      echo "  health CHYBA po 5 pokusech: '$health'" >&2
      failed=1
    fi

    # /actuator/info vrací {"build":{"version":"...","commit":"...", ...}} — "commit" je vlastní
    # additional property z backend/build.gradle (buildInfo), plochý string, ne vnořený objekt.
    info="$(curl -sf "$api_address/actuator/info" || true)"
    deployed_version="$(echo "$info" | grep -o '"version":"[^"]*"' | head -1 | cut -d'"' -f4)"
    deployed_commit="$(echo "$info" | grep -o '"commit":"[^"]*"' | head -1 | cut -d'"' -f4)"

    if [[ "$deployed_version" == "$expected_version" ]]; then
      echo "  verze OK ($deployed_version)"
    else
      echo "  verze NESEDÍ: očekáváno $expected_version, appka hlásí '$deployed_version' ($info)" >&2
      failed=1
    fi

    if [[ -n "$expected_commit" ]]; then
      if [[ -n "$deployed_commit" && "$deployed_commit" == "$expected_commit"* ]]; then
        echo "  commit OK ($deployed_commit)"
      else
        echo "  commit se neshoduje nebo chybí: očekáváno $expected_commit, appka hlásí '$deployed_commit'" >&2
        failed=1
      fi
    fi
  fi

  return "$failed"
}
