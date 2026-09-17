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
pevnými zastaveními popsanými níže (mergnutí PR a shrnutí buildu). Nejde o server (`ops/deploy.sh`
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

6. **Commit na krátkodobé větvi, ne na `main`.** `main` je chráněná GitHub rulesetem „Ochrana
   main" (PR + 5 zelených status checků, bez výjimky i pro admina) — přímý push i tag na `main`
   by ruleset odmítl:
   ```bash
   git checkout -b release/X.Y.Z
   git add <jen těch 7 souborů z kroku 5>
   git commit -m 'Vydat X.Y.Z'
   git push -u origin release/X.Y.Z
   gh pr create --title 'Vydat X.Y.Z' --body '<shrnutí changelogu>'
   ```

7. **Počkej na CI** (`gh pr checks <číslo>` / Monitor) — všech 5 checků (Backend, Frontend,
   Mobile, Dokumentace, Verze a changelog) musí být zelených, jinak merge ruleset odmítne.

8. **Zastavení č. 1 — potvrzení mergnutí PR.** Merge jde na sdílený `main`, zeptej se
   uživatele před `gh pr merge` (a počítej s tím, že `gh pr merge` může být sám o sobě
   zablokovaný auto-mode klasifikátorem — pak požádej uživatele, ať smerguje ručně přes GitHub
   UI nebo svůj shell, a počkej na potvrzení). Metoda mergnutí (merge/squash/rebase) je jedno —
   commit na `main` může dostat nový hash bez ohledu na volbu, tag se řeší až v dalším kroku.

9. **Tag založ AŽ na commitu, který skutečně přistál na `main`, ne dřív:**
   ```bash
   git fetch origin main
   git log -1 --format='%H %s' origin/main   # ověř, že je to "Vydat X.Y.Z"
   git tag -a vX.Y.Z $(git rev-parse origin/main) -m 'Verze X.Y.Z'
   git push origin vX.Y.Z
   ```
   Tag založený PŘED mergí by mohl skončit mimo historii `main` (GitHub umí i u čistě
   dopředného sloučení dát commitu nový hash) a musel by se znovu zakládat a force-pushovat —
   přesně to se stalo u vydání 0.7.2, proto se tenhle krok přesunul až sem. Založení tagu až po
   mergi zároveň umožňuje před schválením PR ještě rebase/fixup, aniž by tag přestal sedět.

10. **Dorovnej lokální `main` a ukliď větev:**
    ```bash
    git checkout main
    git merge --ff-only origin/main || git reset --hard origin/main
    git branch -d release/X.Y.Z   # na originu smaže GitHub sám (delete_branch_on_merge)
    ```

11. **Sestav mobil ve worktree, ne v hlavním checkoutu** (`main` má zůstat na `HEAD`, ne
    v detached state):
    ```bash
    WORKDIR=$(mktemp -d -t kvalitacena-release-vX.Y.Z-XXXX)
    git worktree add "$WORKDIR" vX.Y.Z
    cd "$WORKDIR/mobile" && ./gradlew :app:publishableBundle
    ```
    Podpisový klíč je v `~/.gradle/gradle.properties` (`KVALITACENA_STORE_*`) — Gradle ho najde
    automaticky, nic dalšího není potřeba. Na rozdíl od `bundleRelease` (ten bez klíče tiše
    vyrobí nepodepsaný AAB) `publishableBundle` bez klíče SELŽE samo, s hláškou, která
    vyjmenuje chybějící `KVALITACENA_*` hodnotu, a navíc jarsignerem ověří podpis hotového
    souboru — žádná další kontrola není potřeba (`docs/vydani.md`, „Podpisový klíč"). Nový
    worktree nemá `mobile/local.properties` (negituje se) — zkopíruj ho z hlavního checkoutu,
    jinak build spadne na chybějící `sdk.dir`.

12. **Ulož výstup mimo dočasný worktree** (worktree se za chvíli smaže):
    ```bash
    mkdir -p ~/releases/kvalitacena/vX.Y.Z
    cp "$WORKDIR/mobile/app/build/outputs/bundle/release/app-release.aab" \
       ~/releases/kvalitacena/vX.Y.Z/
    ```

13. **Úklid:**
    ```bash
    git worktree remove "$WORKDIR"
    ```

14. **Zastavení č. 2 — shrnutí.** Napiš: cílovou verzi, cestu k `.aab`, `versionCode`, a
    připomeň dvě věci z `docs/vydani.md`, pokud se týkají téhle verze:
    - jestli `app.client.min-android-version` potřebuje bump na nový `versionCode`,
    - že server (`./ops/deploy.sh X.Y.Z`) tenhle příkaz nespouští — to je samostatný krok.

    Shrnutí vždy končí textem z kroku 15.

15. **Poznámky k vydání pro Google Play.** Na úplný konec shrnutí napiš hotový text do pole
    „Poznámky k vydání" v Play Console, ať ho uživatel nemusí vymýšlet:
    - zdroj je `CHANGELOG.md` od poslední verze nahrané do Play — typicky jen sekce právě
      vydané `X.Y.Z`; pokud některá předchozí verze do Play nešla, zahrň i její sekci,
    - jen to, co pozná uživatel mobilní appky (položky se suffixem obsahujícím `mobil`;
      serverové a webové jen tehdy, když se projeví přímo v appce),
    - v pěti jazycích appky — čeština, angličtina, slovenština, polština, němčina (stejné
      jako `values-*` v `mobile/app/src/main/res`); obsah všech verzí stejný, každá psaná
      přirozeně v daném jazyce, ne doslovný překlad,
    - srozumitelně pro běžného uživatele, bez vývojářské terminologie, krátké odrážky,
    - limit Play Console je 500 znaků na jazyk — spočítej je a počty uveď pod textem,
    - každý jazyk obal do tagu Play Console (`<cs-CZ>`, `<en-US>`, `<sk>`, `<pl-PL>`,
      `<de-DE>`) a všechny dej do jednoho bloku kódu — Play Console ho přijme najednou
      a rozdělí podle tagů; tag musí odpovídat jazyku, který má záznam v Play Console
      přidaný, jinak ho vložení odmítne,
    - když se appky nic viditelného netýká, stačí obecná věta („Drobné opravy a vylepšení."
      a její ekvivalent v ostatních jazycích).

## Co tenhle příkaz neřeší

Hotfix už vydané starší verze (`docs/vydani.md`, „Hotfix už vydané verze" — větev
`release/X.Y.x`) má jiný postup, nepoužívej na něj tenhle flow. Nasazení serveru je
`./ops/deploy.sh X.Y.Z`, samostatně.
