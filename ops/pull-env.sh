#!/usr/bin/env bash
# Stáhne produkční `.env` ze serveru a uloží ho na lokální stroj ZAŠIFROVANÝ
# (backup/kvalitacena-prod.env.gpg, gitignored). Čistý text se na lokální disk vůbec nezapíše —
# jde rourou ze ssh rovnou do gpg.
#
# Proč šifrovat: v `.env` je EMAIL_ENC_KEY, JWT_SECRET a heslo k databázi. Kdo má tenhle soubor
# a zálohu DB (leží ve stejné složce a putují spolu na externí disk a NAS), přečte e-maily všech
# uživatelů. Samotná záloha DB bez klíče je nečitelná — proto klíč nesmí ležet vedle ní v čistém
# textu.
#
# Spouští se RUČNĚ (po změně `.env` na serveru), ne z cronu: gpg se ptá na heslo. Heslo patří do
# správce hesel, ne na disk vedle zálohy — bez něj je soubor k ničemu, takže ho neztratit.
#
# Použití:
#   ./ops/pull-env.sh
# Obnova (čistý text jen na výstup, případně rourou rovnou na server):
#   gpg --decrypt backup/kvalitacena-prod.env.gpg
#   gpg --decrypt backup/kvalitacena-prod.env.gpg | ssh <server> 'umask 077; cat > kvalita-cena/.env'
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_DIR="${LOCAL_BACKUP_DIR:-$REPO_DIR/backup}"
SERVER_HOST="${SERVER_HOST:-178.105.119.39}"
REMOTE_ENV="${REMOTE_ENV:-kvalita-cena/.env}"
TARGET="$LOCAL_DIR/kvalitacena-prod.env.gpg"
PLAINTEXT="$LOCAL_DIR/kvalitacena-prod.env"

command -v gpg >/dev/null || { echo "Chybí gpg (sudo apt install gnupg)." >&2; exit 1; }
mkdir -p "$LOCAL_DIR"

# Do dočasného souboru a přejmenovat až po úspěchu — přerušené stahování nebo překlep v hesle
# nesmí přepsat poslední dobrou kopii useknutým souborem.
tmp="$(mktemp "$LOCAL_DIR/.kvalitacena-prod.env.gpg.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

echo "Stahuji $SERVER_HOST:$REMOTE_ENV a šifruji (gpg se dvakrát zeptá na heslo)..."
# `pipefail` výš: selže-li ssh, selže celá roura a prázdný vstup se jako záloha neuloží.
# --no-symkey-cache: gpg-agent si heslo nepamatuje, další spuštění se zeptá znovu.
# shellcheck disable=SC2029  # REMOTE_ENV se má rozvinout tady, ne na serveru
ssh "$SERVER_HOST" "cat '$REMOTE_ENV'" \
  | gpg --symmetric --cipher-algo AES256 --no-symkey-cache --yes \
        --output "$tmp" -

# Prázdný .env by gpg zašifroval bez chyby — ověřit, že uvnitř něco je, by znamenalo ptát se na
# heslo potřetí; stačí velikost (hlavička gpg + prázdný vstup má pod 100 bajtů).
if (( $(stat -c %s "$tmp") < 200 )); then
  echo "Zašifrovaný soubor je podezřele malý — .env na serveru je prázdný nebo se nestáhl." >&2
  exit 1
fi

chmod 600 "$tmp"
mv "$tmp" "$TARGET"
trap - EXIT
echo "Uloženo: $TARGET"

if [[ -f "$PLAINTEXT" ]]; then
  echo
  echo "POZOR: vedle leží starší NEŠIFROVANÁ kopie $PLAINTEXT." >&2
  echo "Po ověření nové zálohy (gpg --decrypt \"$TARGET\" | head -3) ji smaž:" >&2
  echo "  shred -u \"$PLAINTEXT\"" >&2
  echo "a stejně tak její kopie na externím disku a NAS." >&2
fi
