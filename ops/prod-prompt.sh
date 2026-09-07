# Prompt produkčního serveru — bílý štítek "PRODUKCE" na červeném pozadí, ať je na první pohled
# vidět, že tohle není terminál na lokálním PC (docs/nasazeni.md, sekce 2). Lokální Ubuntu prompt
# je zelený `user@host` + modrá cesta, přes ssh vypadal server dřív úplně stejně — a přitom tady
# jsou příkazy, které jinde nevadí a tady jsou nevratné (`docker compose -f compose.prod.yaml
# down -v` vezme produkční databázi i certifikáty naráz).
#
# Instalace na serveru (soubor se KOPÍRUJE, nesourcuje se z checkoutu repa — viz ops/README.md):
#   scp ops/prod-prompt.sh kvalitacena@<server>:~/.prod-prompt.sh
#   # na serveru, na KONEC ~/.bashrc (default blok by PS1 jinak přepsal zpátky):
#   [ -f ~/.prod-prompt.sh ] && . ~/.prod-prompt.sh
#
# Záměrně BEZ `set -euo pipefail` a bez práva `+x` — na rozdíl od ostatních skriptů v ops/ se
# tenhle sourcuje do interaktivního shellu (jako lib-retention.sh), kde by `set -e` zavřel okno
# při každém neúspěšném příkazu.

# Jen interaktivní shell — scp/rsync/cron PS1 nepotřebují a rozbitý výstup by je shodil.
case $- in
  *i*) ;;
  *) return 0 ;;
esac

# Barvy jen když je terminál umí (stejný test jako výchozí ~/.bashrc); jinak prostý text, ať
# v promptu nezůstane ANSI smetí.
if [ -x /usr/bin/tput ] && tput setaf 1 >/dev/null 2>&1; then
  __kc_badge='\[\e[97;41m\] PRODUKCE \[\e[0m\]'
  __kc_host='\[\e[1;31m\]\u@\h\[\e[0m\]'
  __kc_path='\[\e[1;34m\]\w\[\e[0m\]'
else
  __kc_badge='[PRODUKCE]'
  __kc_host='\u@\h'
  __kc_path='\w'
fi

# Escapy barev musí být v \[ \], jinak bash počítá špatně šířku promptu a rozbije se zalamování
# dlouhých řádků a historie.
PS1="$__kc_badge $__kc_host:$__kc_path\\\$ "

# Titulek okna/záložky terminálu — ať je server poznat i podle záložky, ne jen podle promptu.
case "$TERM" in
  xterm*|rxvt*) PS1="\[\e]0;PRODUKCE \u@\h \w\a\]$PS1" ;;
esac

unset __kc_badge __kc_host __kc_path
