#!/usr/bin/env bash
# Sdílené funkce pro úklid záloh (ops/backup.sh, ops/pull-backup.sh) — rotace logu a mazání
# starých souborů podle stáří/počtu/GFS schématu. Timestamp v názvu souboru (`date +%F-%H%M`,
# např. `db-2026-08-23-2043.sql.gz`) je autoritativní, ne `mtime` souboru: rsync při stahování
# na lokální PC (ops/pull-backup.sh) mtime typicky zachová, ale spoléhat na to by bylo křehké —
# název je to, co skript sám vygeneroval a co nezmění žádný přenos.
#
# PRUNE_DRY_RUN=1 přepne prune_count/prune_gfs na "jen vypsat, co by se smazalo" — použít před
# prvním nasazením proti reálným datům (ops/README.md, "Retence a úklid").

# Vytáhne z názvu souboru timestamp `YYYY-MM-DD-HHMM` (první výskyt v basename).
_retention_timestamp_from_name() {
  local name
  name="$(basename "$1")"
  [[ "$name" =~ ([0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{4}) ]] && echo "${BASH_REMATCH[1]}"
}

_retention_delete() {
  local file="$1"
  if [[ "${PRUNE_DRY_RUN:-0}" == "1" ]]; then
    echo "  [dry-run] smazal bych: $file"
  else
    echo "  mažu: $file"
    rm -f -- "$file"
  fi
}

# rotate_log <soubor> <max_bajtů> <ponechat_řádků>
#
# Když log přeroste limit, zkrátí ho na posledních N řádků IN-PLACE (přepíše obsah stejného
# inode, nepřejmenuje soubor). Cron drží log otevřený přes `>>` (O_APPEND) po celou dobu běhu
# skriptu — kdyby se soubor přejmenoval (`mv log log.old`), file descriptor cronu by dál mířil
# na starý inode a zbytek běhu by se zapsal do souboru, který už nikdo nečte. Zkrácení obsahu
# při zachování inode je vůči tomu bezpečné: další zápis přes O_APPEND pokračuje od nového konce.
rotate_log() {
  local log_file="$1" max_bytes="$2" keep_lines="$3"
  [[ -f "$log_file" ]] || return 0
  local size
  size="$(stat -c%s "$log_file" 2>/dev/null || stat -f%z "$log_file" 2>/dev/null || echo 0)"
  if (( size > max_bytes )); then
    local tmp
    tmp="$(mktemp "${log_file}.XXXXXX")"
    tail -n "$keep_lines" "$log_file" > "$tmp"
    # `>` na existující soubor otevírá s O_TRUNC na STEJNÉM inode, takže obsah se zkrátí a
    # nahradí, ale deskriptor cronu (otevřený přes `>>`, tedy O_APPEND) zůstává platný a další
    # zápis pokračuje od nového (kratšího) konce souboru.
    cat "$tmp" > "$log_file"
    rm -f "$tmp"
  fi
}

# prune_count <adresář> <vzor> <max_počet>
#
# Nechá N nejnovějších souborů matchujících <vzor> (glob, např. 'db-*.sql.gz'), ostatní smaže.
# "Nejnovější" se určuje podle NÁZVU, ne mtime — timestamp z `date +%F-%H%M` je lexikograficky
# stejné pořadí jako chronologicky, a název je to, co skript sám vygeneroval (na rozdíl od mtime
# ho nezmění rsync ani ruční přesun).
prune_count() {
  local dir="$1" pattern="$2" max_count="$3"
  local -a files=()
  local file
  for file in "$dir"/$pattern; do
    [[ -e "$file" ]] && files+=("$file")
  done
  (( ${#files[@]} <= max_count )) && return 0
  local -a sorted
  mapfile -t sorted < <(printf '%s\n' "${files[@]}" | sort)
  local to_delete=$(( ${#sorted[@]} - max_count ))
  local i
  for (( i = 0; i < to_delete; i++ )); do
    _retention_delete "${sorted[$i]}"
  done
}

# Převede timestamp `YYYY-MM-DD-HHMM` na epoch sekundy. Vrací nenulový exit kód (a nic
# nevypíše), pokud formát nesedí — volající to musí ošetřit a takový soubor přeskočit.
_retention_epoch_from_timestamp() {
  local ts="$1"
  [[ ${#ts} -eq 15 ]] || return 1
  local day="${ts:0:10}" hhmm="${ts:11:4}"
  date -d "${day} ${hhmm:0:2}:${hhmm:2:2}" +%s 2>/dev/null
}

# retention_keep_set <daily_dnů> <weekly_dnů> <monthly_dnů> <timestamp...>
#
# GFS schéma: pro timestampy staré nejvýš <daily_dnů> nechá nejnovější z každého DNE, pro starší
# (do <weekly_dnů>) nejnovější z každého ISO TÝDNE, pro ještě starší (do <monthly_dnů>) nejnovější
# z každého MĚSÍCE; nad <monthly_dnů> nezůstane nic. Vypíše (řádek po řádku) timestampy, které
# mají zůstat — volající tím samým seznamem prořeže i párový soubor (média k dané záloze DB),
# takže dvojice `db-`/`media-` zůstávají svázané i po úklidu.
retention_keep_set() {
  local daily_days="$1" weekly_days="$2" monthly_days="$3"
  shift 3
  local now epoch age_days bucket_kind bucket_key key ts
  now="$(date +%s)"
  local -A best_ts_for_bucket=()
  for ts in "$@"; do
    epoch="$(_retention_epoch_from_timestamp "$ts")" || continue
    age_days=$(( (now - epoch) / 86400 ))
    if (( age_days <= daily_days )); then
      bucket_kind="d"; bucket_key="$(date -d "@$epoch" +%F)"
    elif (( age_days <= weekly_days )); then
      bucket_kind="w"; bucket_key="$(date -d "@$epoch" +%G-W%V)"
    elif (( age_days <= monthly_days )); then
      bucket_kind="m"; bucket_key="$(date -d "@$epoch" +%Y-%m)"
    else
      continue
    fi
    key="${bucket_kind}:${bucket_key}"
    if [[ -z "${best_ts_for_bucket[$key]:-}" || "$ts" > "${best_ts_for_bucket[$key]}" ]]; then
      best_ts_for_bucket[$key]="$ts"
    fi
  done
  (( ${#best_ts_for_bucket[@]} > 0 )) && printf '%s\n' "${best_ts_for_bucket[@]}"
  return 0
}

# retention_prune_dir <adresář> <vzor> <keep_timestamps>
#
# Smaže z <adresář>/<vzor> vše, jehož timestamp v názvu není v <keep_timestamps> (výstup
# retention_keep_set, jeden timestamp na řádek). Soubory, ze kterých nejde timestamp přečíst
# (jiný formát názvu — logy, `.env`), nechává vždy na pokoji, ať jde volat i na adresář s
# různorodým obsahem.
retention_prune_dir() {
  local dir="$1" pattern="$2" keep_list="$3"
  local file ts
  for file in "$dir"/$pattern; do
    [[ -e "$file" ]] || continue
    ts="$(_retention_timestamp_from_name "$file")"
    [[ -n "$ts" ]] || continue
    if ! grep -qxF "$ts" <<< "$keep_list"; then
      _retention_delete "$file"
    fi
  done
}

# prune_gfs <adresář> <vzor> <daily_dnů> <weekly_dnů> <monthly_dnů>
#
# Pohodlná varianta retention_keep_set + retention_prune_dir nad jedním vzorem — použít, když
# není potřeba svázat prořezávání s jiným párovým vzorem (viz retention_keep_set výš).
prune_gfs() {
  local dir="$1" pattern="$2" daily_days="$3" weekly_days="$4" monthly_days="$5"
  local -a all_ts=()
  local file ts
  for file in "$dir"/$pattern; do
    [[ -e "$file" ]] || continue
    ts="$(_retention_timestamp_from_name "$file")"
    [[ -n "$ts" ]] && all_ts+=("$ts")
  done
  local keep_list
  keep_list="$(retention_keep_set "$daily_days" "$weekly_days" "$monthly_days" "${all_ts[@]}")"
  retention_prune_dir "$dir" "$pattern" "$keep_list"
}
