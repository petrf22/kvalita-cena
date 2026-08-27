#!/usr/bin/env node
// Sjednocené verzování napříč monorepem (docs/vydani.md, sekce "Verzování a vydání").
// Kořenové VERSION + CHANGELOG.md jsou jediný zdroj pravdy — tenhle skript z nich přepisuje
// pět commitovaných výstupů (backend/build.gradle, frontend/package.json,
// mobile/app/build.gradle.kts a generované seznamy změn pro web i mobil). Spouští se ručně při
// vydání a v CI (.github/workflows/ci.yml, job "Verze a changelog") jako `node
// tools/version/sync.mjs` následované `git diff --exit-code` — stejný vzor jako
// frontend/tools/version/write-version.mjs + graphql-codegen. Needituj generované soubory
// ručně, uprav VERSION/CHANGELOG.md a spusť skript znovu.
//
// compose.prod.yaml staví backend i frontend s Docker build kontextem uvnitř appky
// (`context: ./backend`, `context: ./frontend`) — kořenový adresář v něm není vidět, proto se
// verze i changelog generují dopředu a commitují, ne až při buildu obrazu.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const GENERATED_NOTICE =
  '// Generováno tools/version/sync.mjs z kořenového VERSION — needituj ručně.';

function read(relPath) {
  return readFileSync(path.join(ROOT, relPath), 'utf8');
}

function write(relPath, content) {
  const absPath = path.join(ROOT, relPath);
  mkdirSync(path.dirname(absPath), { recursive: true });
  writeFileSync(absPath, content);
}

// --- 1. Načíst VERSION -------------------------------------------------------------------

const version = read('VERSION').trim();
const versionMatch = version.match(/^(\d+)\.(\d+)\.(\d+)$/);
if (!versionMatch) {
  throw new Error(`VERSION musí být SemVer X.Y.Z, je: "${version}"`);
}
const [, majorStr, minorStr, patchStr] = versionMatch;
const [major, minor, patch] = [majorStr, minorStr, patchStr].map(Number);
if (minor > 99 || patch > 99) {
  throw new Error(
    `versionCode = major*10000 + minor*100 + patch přetéká pro ${version} — minor i patch musí být < 100`,
  );
}
const versionCode = major * 10000 + minor * 100 + patch;

// --- 2. Naparsovat CHANGELOG.md ----------------------------------------------------------

const changelogRaw = read('CHANGELOG.md');
const lines = changelogRaw.split('\n');

const RELEASE_HEADER = /^## \[(\d+\.\d+\.\d+)\] [–-] (\d{4}-\d{2}-\d{2})$/;
const SECTION_HEADER = /^### (.+)$/;
const ITEM_LINE = /^- (.+)$/;
const PARTS_SUFFIX = /^(.*?)\s*\(([a-zěščřžýáíéúůťďňA-ZĚŠČŘŽÝÁÍÉÚŮŤĎŇ, ]+)\)\s*$/;

/** @type {{version: string, date: string, sections: {title: string, items: {text: string, parts: string[]}[]}[]}[]} */
const releases = [];
let currentRelease = null;
let currentSection = null;
let inUnreleased = false;

for (const line of lines) {
  if (line.trim() === '## [Nezveřejněno]') {
    inUnreleased = true;
    currentRelease = null;
    currentSection = null;
    continue;
  }
  const releaseHeader = line.match(RELEASE_HEADER);
  if (releaseHeader) {
    inUnreleased = false;
    currentRelease = { version: releaseHeader[1], date: releaseHeader[2], sections: [] };
    releases.push(currentRelease);
    currentSection = null;
    continue;
  }
  if (inUnreleased || !currentRelease) continue;

  const sectionHeader = line.match(SECTION_HEADER);
  if (sectionHeader) {
    currentSection = { title: sectionHeader[1].trim(), items: [] };
    currentRelease.sections.push(currentSection);
    continue;
  }

  const item = line.match(ITEM_LINE);
  if (item && currentSection) {
    const partsMatch = item[1].match(PARTS_SUFFIX);
    if (partsMatch) {
      currentSection.items.push({
        text: partsMatch[1].trim(),
        parts: partsMatch[2].split(',').map((p) => p.trim()),
      });
    } else {
      currentSection.items.push({ text: item[1].trim(), parts: [] });
    }
    continue;
  }

  // Cokoli neprázdného uvnitř vydání, co není hlavička ani položka, je nejspíš omylem zalomená
  // víceřádková položka (parser umí jen jednořádkové "- text") — raději spadnout, než ji tiše
  // uříznout.
  if (line.trim() !== '') {
    throw new Error(
      `CHANGELOG.md: nerozpoznaný řádek uvnitř vydání ${currentRelease.version} — víceřádková ` +
        `položka? Každá položka musí být na jednom řádku. Řádek: "${line}"`,
    );
  }
}

if (releases.length === 0) {
  throw new Error('CHANGELOG.md neobsahuje žádné vydání ("## [X.Y.Z] – YYYY-MM-DD")');
}
if (releases[0].version !== version) {
  throw new Error(
    `CHANGELOG.md nemá sekci pro ${version} — nejnovější vydání v CHANGELOG.md je ${releases[0].version}. ` +
      `Zapiš "## [${version}] – YYYY-MM-DD" do CHANGELOG.md, nebo oprav VERSION.`,
  );
}

// --- 3. backend/build.gradle ---------------------------------------------------------------

{
  const relPath = 'backend/build.gradle';
  let content = read(relPath);
  const withNotice = new RegExp(
    `(?:^${escapeRegExp(GENERATED_NOTICE)}\\n)?^version = '[^']*'$`,
    'm',
  );
  if (!withNotice.test(content)) {
    throw new Error(`${relPath}: nenašel jsem řádek "version = '...'"`);
  }
  content = content.replace(withNotice, `${GENERATED_NOTICE}\nversion = '${version}'`);
  write(relPath, content);
}

// --- 4. frontend/package.json --------------------------------------------------------------
// JSON nepodporuje komentáře, verze se přepisuje bez doprovodné poznámky (viz komentář nahoře).

{
  const relPath = 'frontend/package.json';
  let content = read(relPath);
  const versionField = /^(\s*"version":\s*")[^"]*(",?)$/m;
  if (!versionField.test(content)) {
    throw new Error(`${relPath}: nenašel jsem pole "version"`);
  }
  content = content.replace(versionField, `$1${version}$2`);
  write(relPath, content);
}

// --- 5. mobile/app/build.gradle.kts --------------------------------------------------------

{
  const relPath = 'mobile/app/build.gradle.kts';
  let content = read(relPath);
  const withNotice = new RegExp(
    `(?:^( *)${escapeRegExp(GENERATED_NOTICE)}\\n)?^( *)versionCode = \\d+\\n( *)versionName = "[^"]*"$`,
    'm',
  );
  const match = content.match(withNotice);
  if (!match) {
    throw new Error(`${relPath}: nenašel jsem "versionCode"/"versionName"`);
  }
  const indent = match[2] ?? match[1];
  content = content.replace(
    withNotice,
    `${indent}${GENERATED_NOTICE}\n${indent}versionCode = ${versionCode}\n${indent}versionName = "${version}"`,
  );
  write(relPath, content);
}

// --- 6. Generované seznamy změn pro klienty -------------------------------------------------

function tsStringLiteral(s) {
  return JSON.stringify(s);
}

function releaseToTsObject(release, indent) {
  const sections = release.sections
    .map((section) => {
      const items = section.items
        .map(
          (item) =>
            `${indent}      { text: ${tsStringLiteral(item.text)}, parts: [${item.parts
              .map(tsStringLiteral)
              .join(', ')}] },`,
        )
        .join('\n');
      return `${indent}    { title: ${tsStringLiteral(section.title)}, items: [\n${items}\n${indent}    ] },`;
    })
    .join('\n');
  return (
    `${indent}{ version: ${tsStringLiteral(release.version)}, date: ${tsStringLiteral(release.date)}, sections: [\n` +
    `${sections}\n` +
    `${indent}] },`
  );
}

{
  const relPath = 'frontend/src/app/changelog.generated.ts';
  const body = releases.map((r) => releaseToTsObject(r, '  ')).join('\n');
  const content =
    '// Generováno tools/version/sync.mjs z kořenového CHANGELOG.md — needituj ručně.\n' +
    '// Zobrazuje features/changelog/changelog-page.ts, text je záměrně jen česky (CLAUDE.md,\n' +
    '// docs/lokalizace.md — legal.czechOnlyNotice vzor), UI okolo je přeložené.\n' +
    '\n' +
    'export interface ChangelogItem {\n' +
    '  text: string;\n' +
    '  parts: string[];\n' +
    '}\n' +
    '\n' +
    'export interface ChangelogSection {\n' +
    '  title: string;\n' +
    '  items: ChangelogItem[];\n' +
    '}\n' +
    '\n' +
    'export interface ChangelogRelease {\n' +
    '  version: string;\n' +
    '  date: string;\n' +
    '  sections: ChangelogSection[];\n' +
    '}\n' +
    '\n' +
    '// Nejnovější vydání první.\n' +
    'export const CHANGELOG: ChangelogRelease[] = [\n' +
    `${body}\n` +
    '];\n';
  write(relPath, content);
}

{
  const relPath = 'mobile/app/src/main/assets/changelog.json';
  write(relPath, JSON.stringify(releases, null, 2) + '\n');
}

console.log(`Verze synchronizována: ${version} (versionCode ${versionCode})`);

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
