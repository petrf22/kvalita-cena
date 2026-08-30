---
description: Vydat novou verzi appky — povýšit VERSION/CHANGELOG, otagovat, odeslat a sestavit podepsaný mobilní release (AAB)
argument-hint: [X.Y.Z|major|minor|patch]
arguments: [bump]
---

## Aktuální stav

Verze: !`cat VERSION`

Poslední tagy: !`git tag --list | sort -V | tail -5`

Větev a čistota stromu: !`git status -sb`

Commity od posledního tagu: !`git log $(git tag --list | sort -V | tail -1)..HEAD --oneline`

Sekce `[Nezveřejněno]` v CHANGELOG.md: !`grep -n "Nezveřejněno" CHANGELOG.md || echo "(žádná)"`

Dnešní datum: !`date +%Y-%m-%d`

## Postup (viz `docs/vydani.md`, „Postup vydání" a „Z čeho stavět")

Tohle je opakovaný release flow — proveď ho celý sám, bez zbytečných dotazů, ale se dvěma
pevnými zastaveními popsanými níže (tag/push a shrnutí buildu). Nejde o server (`ops/deploy.sh`
se tímto příkazem nespouští, jen mobil). Příkazy níže používají `X.Y.Z`/`vX.Y.Z` jako placeholder
za skutečně spočítanou verzi (krok 1) — dosaď ji, než příkaz spustíš.

0. **Předpoklady.** Větev musí být `main` a `git status --short` prázdný — jinak se zastav
   a vypiš, co brání (rozpracovaná práce se nesmí tiše zabalit do release commitu).

1. **Zjisti cílovou verzi.** Argument `$bump`:
   - tvar `X.Y.Z` → použij přímo,
   - `major`/`minor`/`patch` → spočítej SemVer bump z aktuální hodnoty `VERSION` výše,
   - prázdné → `patch` bump.

2. **Aktualizuj `CHANGELOG.md`.**
   - Pokud existuje sekce `## [Nezveřejněno]` s položkami, přejmenuj ji na
     `## [X.Y.Z] – <dnešní datum>`.
   - Pokud neexistuje, over commity od posledního tagu (výpis výše) a navrhni položky do
     Přidáno/Změněno/Opraveno — česky, jedna položka na řádek, se suffixem `(server, web,
     mobil)` podle toho, čeho se týkají (konvence `CLAUDE.md` a existující sekce v souboru).
     **Ukaž návrh a počkej na potvrzení/úpravu**, než ho zapíšeš — changelog čtou lidi, ne CI.
   - Nikdy nezapisuj víceřádkovou položku (`tools/version/sync.mjs` to neumí naparsovat).

3. **Zapiš `X.Y.Z` do `VERSION`.**

4. **Spusť sync:**
   ```bash
   source ~/.nvm/nvm.sh && nvm use 24
   node tools/version/sync.mjs
   ```
   Skript končí chybou, pokud si `VERSION` a nejnovější sekce `CHANGELOG.md` neodpovídají.

5. **Zkontroluj `git diff --stat`** — měly by být jen `VERSION`, `CHANGELOG.md` a pět
   generovaných souborů (`backend/build.gradle`, `frontend/package.json`,
   `mobile/app/build.gradle.kts`, `frontend/src/app/changelog.generated.ts`,
   `mobile/app/src/main/assets/changelog.json`). Cokoli navíc je podezřelé — zastav se.

6. **Commit:** `Vydat X.Y.Z` (jen tyhle soubory, ne `git add -A`).

7. **Zastavení č. 1 — potvrzení tagu a pushe.** Shrň verzi a changelog diff a zeptej se
   uživatele, než uděláš cokoli, co jde na sdílený vzdálený repozitář:
   ```bash
   git tag -a vX.Y.Z -m 'Verze X.Y.Z'
   git push --follow-tags
   ```

8. **Sestav mobil ve worktree, ne v hlavním checkoutu** (`main` má zůstat na `HEAD`, ne
   v detached state):
   ```bash
   WORKDIR=$(mktemp -d -t kvalitacena-release-vX.Y.Z-XXXX)
   git worktree add "$WORKDIR" vX.Y.Z
   cd "$WORKDIR/mobile" && ./gradlew :app:bundleRelease
   ```
   Podpisový klíč je v `~/.gradle/gradle.properties` (`KVALITACENA_STORE_*`) — Gradle ho najde
   automaticky, nic dalšího není potřeba. Pokud `signingConfig` chybí (nepodepsaný výstup),
   zastav se a upozorni — nejspíš chybí některá z `KVALITACENA_*` hodnot
   (`docs/vydani.md`, „Podpisový klíč").

9. **Ulož výstup mimo dočasný worktree** (worktree se za chvíli smaže):
   ```bash
   mkdir -p ~/releases/kvalitacena/vX.Y.Z
   cp "$WORKDIR/mobile/app/build/outputs/bundle/release/app-release.aab" \
      ~/releases/kvalitacena/vX.Y.Z/
   ```

10. **Úklid:**
    ```bash
    git worktree remove "$WORKDIR"
    ```

11. **Zastavení č. 2 — shrnutí.** Napiš: cílovou verzi, cestu k `.aab`, `versionCode`, a
    připomeň dvě věci z `docs/vydani.md`, pokud se týkají téhle verze:
    - jestli `app.client.min-android-version` potřebuje bump na nový `versionCode`,
    - že server (`./ops/deploy.sh X.Y.Z`) tenhle příkaz nespouští — to je samostatný krok.

## Co tenhle příkaz neřeší

Hotfix už vydané starší verze (`docs/vydani.md`, „Hotfix už vydané verze" — větev
`release/X.Y.x`) má jiný postup, nepoužívej na něj tenhle flow. Nasazení serveru je
`./ops/deploy.sh X.Y.Z`, samostatně.
