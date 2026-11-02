#!/usr/bin/env bash
# Kurátorství označení z účtenek proti produkčnímu serveru (README.md, „Kurátorství").
# Další argumenty se předají curate.py, KC_API_URL jde přebít proměnnou prostředí.
set -euo pipefail

token_file="$HOME/.config/kvalitacena-curator-token"
if [[ ! -r "$token_file" ]]; then
  echo "Chybí token kurátora: $token_file (README.md, „Kurátorství\")" >&2
  exit 1
fi

export KC_API_URL="${KC_API_URL:-https://api.kvalitacena.cz}"
export KC_CURATOR_TOKEN="$(cat "$token_file")"

exec python3 "$(dirname "$0")/curate.py" "$@"
