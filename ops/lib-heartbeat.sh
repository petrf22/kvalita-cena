#!/usr/bin/env bash
# „Heartbeat" pro zálohy (ops/backup.sh, ops/pull-backup.sh) — skript po úspěchu pingne adresu
# u externí služby (healthchecks.io) a ta pošle e-mail, když ping NEPŘIJDE včas.
#
# Proč zvenčí a ne dalším hlášením ve skriptu: obě dosavadní tichá selhání záloh (cron na serveru
# 5 dní v 2026-08, pull-backup.sh od 2026-09-19 do 2026-10-28) měla společné to, že se skript
# vůbec nespustil — spadlo už přesměrování logu v crontabu. Hlášení uvnitř skriptu na to
# z principu nestačí; mlčení pozná jen někdo, kdo ping čeká (ops/README.md, „Monitorování").
#
# ping_heartbeat <url> [start|fail]
#   bez druhého argumentu = úspěch; `start` = běh začal (služba pak měří i dobu běhu a pozná
#   běh, který začal a nedoběhl); `fail` = běh selhal, upozornit hned a nečekat na lhůtu.
#
# Prázdná URL = nic se neděje (vývojový stroj, zkouška nanečisto). Selhání pingu se jen vypíše
# a NIKDY neshodí zálohu — výpadek hlídací služby nesmí znamenat den bez zálohy.
ping_heartbeat() {
  local url="${1:-}" signal="${2:-}"
  [[ -n "$url" ]] || return 0
  [[ -n "$signal" ]] && url="${url%/}/$signal"
  curl -fsS -m 10 --retry 3 --retry-delay 2 -o /dev/null "$url" \
    || echo "Pozor: heartbeat ping (${signal:-úspěch}) se nepodařil — záloha tím není dotčena." >&2
  return 0
}
