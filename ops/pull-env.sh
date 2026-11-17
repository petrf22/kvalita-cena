#!/usr/bin/env bash
# Spouští se na PC provozovatele, NE na serveru. Stáhne produkční `.env` ze serveru přes ssh
# a uloží ho na lokální stroj ZAŠIFROVANÝ (backup/kvalitacena-prod.env.gpg, gitignored). Čistý
# text se na lokální disk vůbec nezapíše — drží ho jen proměnná v paměti, než ho převezme gpg.
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

# Skript patří na PC provozovatele, ne na server — tam by se ssh připojovalo samo k sobě
# a šifrovaná kopie by ležela vedle originálu, kde je k ničemu (2026-11-17 se to stalo).
if hostname -I 2>/dev/null | tr ' ' '\n' | grep -qxF "$SERVER_HOST"; then
  echo "Tohle je server $SERVER_HOST — pull-env.sh se spouští na tvém PC, ne na serveru." >&2
  exit 1
fi

mkdir -p "$LOCAL_DIR"

# Do dočasného souboru a přejmenovat až po úspěchu — přerušené stahování nebo překlep v hesle
# nesmí přepsat poslední dobrou kopii useknutým souborem.
tmp="$(mktemp "$LOCAL_DIR/.kvalitacena-prod.env.gpg.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

echo "Stahuji $SERVER_HOST:$REMOTE_ENV..."
# Nejdřív celé stáhnout, až potom šifrovat — v rouře ssh | gpg se oba ptaly na terminál
# zároveň (ssh na otisk neznámého serveru, gpg na heslo) a gpg skončil s „Operation
# cancelled". Obsah drží jen proměnná v paměti tohohle procesu, na disk se nezapíše.
# shellcheck disable=SC2029  # REMOTE_ENV se má rozvinout tady, ne na serveru
env_content="$(ssh "$SERVER_HOST" "cat '$REMOTE_ENV'")"
if [[ -z "$env_content" ]]; then
  echo "Ze serveru přišel prázdný .env — nic neukládám." >&2
  exit 1
fi

echo "Šifruji — zadej heslo..."
# --pinentry-mode loopback + GPG_TTY: heslo se zadá přímo v terminálu, i bez grafického
# pinentry (ssh relace, čistá konzole), kde gpg jinak padá na „Inappropriate ioctl for device".
# --no-symkey-cache: gpg-agent si heslo nepamatuje, další spuštění se zeptá znovu.
GPG_TTY="$(tty)"
export GPG_TTY
printf '%s\n' "$env_content" \
  | gpg --symmetric --cipher-algo AES256 --no-symkey-cache --pinentry-mode loopback --yes \
        --output "$tmp" -

# V režimu loopback se gpg na zopakování hesla NEPTÁ, překlep by tedy nikdo nechytil a záloha
# by šla rozšifrovat jen překlepnutým heslem. Místo zopakování proto soubor rovnou rozšifrovat
# a porovnat se staženým obsahem — chytí to překlep i poškozený zápis. Porovnává se v paměti.
echo "Ověřuji zálohu — zadej heslo ještě jednou..."
if ! decrypted="$(gpg --quiet --decrypt --no-symkey-cache --pinentry-mode loopback "$tmp")" \
   || [[ "$decrypted" != "$env_content" ]]; then
  unset env_content decrypted
  echo "Zálohu nejde rozšifrovat zadaným heslem (překlep?) — nic neukládám, zkus to znovu." >&2
  exit 1
fi
unset env_content decrypted

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
