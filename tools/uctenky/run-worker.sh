#!/usr/bin/env bash
# Jedna dávka pull workeru účtenek proti produkčnímu serveru (README.md, „Spuštění").
# Další argumenty se předají workeru, KC_API_URL jde přebít proměnnou prostředí.
set -euo pipefail

token_file="$HOME/.config/kvalitacena-worker-token"
if [[ ! -r "$token_file" ]]; then
  echo "Chybí token workeru: $token_file (README.md, „Token\")" >&2
  exit 1
fi

export KC_API_URL="${KC_API_URL:-https://api.kvalitacena.cz}"
export KC_WORKER_TOKEN="$(cat "$token_file")"

exec python3 "$(dirname "$0")/worker.py" --once "$@"
