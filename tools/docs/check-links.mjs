#!/usr/bin/env node
// Kontrola odkazů v dokumentaci (docs/README.md, „Zdroj rozhodnutí, které v repu nejsou") —
// hlídá tři tvary, kterými se dokumenty v tomhle repu odkazují na sebe navzájem:
//
//   1. klasické markdown odkazy   [text](soubor.md)
//   2. neformální zmínky v backtičkách  `docs/soubor.md`  — dominantní styl v tomhle repu
//      (řádově víc výskytů než klasických odkazů), kterým dřív unikl mrtvý odkaz na
//      `repo_migration.md` (soubor mimo repo, který přestal existovat).
//   3. kotvy uvnitř markdown odkazů  [text](#kotva)  nebo  [text](soubor.md#kotva)  — kotva
//      musí odpovídat GitHub-style slugu nějakého nadpisu v cílovém souboru. Tahle kontrola
//      chytla, že přejmenování nadpisu „cs/sk/en/pl" na „cs/sk/en/pl/de" nechalo v odkazu
//      starou kotvu s doslovnou mezerou uvnitř.
//
// Spouští se ručně (`node tools/docs/check-links.mjs`) i v CI (.github/workflows/ci.yml,
// job „Dokumentace"). Bez závislostí, čistý Node.
import { readFileSync, existsSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..');

// Soubory, které se v repu na sebe navzájem odkazují — docs/, kořenové .md a CLAUDE.md
// v jednotlivých aplikacích. Rozšiřuj, jen když přibude další místo, které dokumentaci píše.
// Žádná knihovna na glob — jen tahle malá pomocná funkce nad readdirSync.
function mdFilesIn(dir) {
  const absDir = path.join(ROOT, dir);
  if (!existsSync(absDir)) return [];
  return readdirSync(absDir)
    .filter((name) => name.endsWith('.md'))
    .map((name) => path.posix.join(dir, name));
}

const TARGET_FILES = [
  ...mdFilesIn('docs'),
  ...mdFilesIn('.'),
  'backend/CLAUDE.md',
  'frontend/CLAUDE.md',
  'mobile/CLAUDE.md',
  'ops/README.md',
  'frontend/README.md',
].filter((relPath) => existsSync(path.join(ROOT, relPath)));

const MARKDOWN_LINK = /\]\(([^)]+)\)/g;
// `docs/neco.md` nebo `neco.md` — jen markdown soubory, ne libovolná cesta v backtičkách
// (ty jsou většinou kódové identifikátory/třídy, ne odkazy).
const BACKTICK_MD_REF = /`((?:\.\.\/)*(?:[a-zA-Z0-9_-]+\/)*[a-zA-Z0-9_-]+\.md)`/g;
const HEADING = /^(#{1,6})\s+(.+?)\s*$/gm;

/**
 * GitHub-style slug z textu nadpisu: markdown formátování (backtičky, **tučné**, [odkazy](...))
 * se nejdřív odstraní na holý text, pak lowercase, odstraní se vše kromě písmen/číslic/mezer/
 * podtržítek/pomlček, mezery -> pomlčky. Druhý a další výskyt stejného slugu v souboru dostane
 * příponu -1/-2/... (GitHub dedupe), proto se slug počítá vždy nad VŠEMI nadpisy souboru
 * najednou, ne nadpis od nadpisu izolovaně.
 */
function slugify(headingText, seenCounts) {
  const plain = headingText
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]*)\*\*/g, '$1')
    .replace(/__([^_]*)__/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1');
  let slug = plain
    .toLowerCase()
    .trim()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s+/g, '-');
  const seen = seenCounts.get(slug) ?? 0;
  seenCounts.set(slug, seen + 1);
  return seen === 0 ? slug : `${slug}-${seen}`;
}

const anchorCache = new Map();

/** Množina platných kotev pro soubor (absolutní cesta), s cachí přes opakovaná volání. */
function anchorsFor(absPath) {
  if (anchorCache.has(absPath)) return anchorCache.get(absPath);
  const anchors = new Set();
  if (existsSync(absPath)) {
    const text = readFileSync(absPath, 'utf8');
    const seenCounts = new Map();
    for (const match of text.matchAll(HEADING)) {
      anchors.add(slugify(match[2], seenCounts));
    }
  }
  anchorCache.set(absPath, anchors);
  return anchors;
}

function checkFile(relPath) {
  const absPath = path.join(ROOT, relPath);
  const text = readFileSync(absPath, 'utf8');
  const baseDir = path.dirname(absPath);
  const problems = [];

  for (const match of text.matchAll(MARKDOWN_LINK)) {
    const target = match[1];
    if (target.startsWith('http://') || target.startsWith('https://')) continue;

    const [targetPath, anchor] = target.split('#');
    const resolvedAbs = targetPath ? path.normalize(path.join(baseDir, targetPath)) : absPath;

    if (targetPath && !existsSync(resolvedAbs)) {
      problems.push(`  markdown odkaz -> ${target}`);
      continue;
    }
    if (anchor !== undefined && resolvedAbs.endsWith('.md')) {
      const anchors = anchorsFor(resolvedAbs);
      if (!anchors.has(anchor)) {
        const displayTarget = targetPath || path.relative(ROOT, absPath);
        problems.push(`  kotva neexistuje -> ${target} (v ${displayTarget} nenalezena „#${anchor}")`);
      }
    }
  }

  for (const match of text.matchAll(BACKTICK_MD_REF)) {
    const target = match[1];
    // Backtičky se v .md souborech používají i pro citaci JMÉNA souboru bez záměru odkazovat
    // (např. "needituj mobile/app/build.gradle.kts ručně") — tenhle skript kontroluje jen
    // .md cíle, u kterých dává smysl, že jsou to odkazy na dokumentaci.
    const resolvedFromRoot = path.normalize(path.join(ROOT, target));
    const resolvedFromHere = path.normalize(path.join(baseDir, target));
    if (!existsSync(resolvedFromRoot) && !existsSync(resolvedFromHere)) {
      problems.push(`  backtick odkaz -> \`${target}\``);
    }
  }

  return problems;
}

let hasProblems = false;
for (const relPath of [...new Set(TARGET_FILES)].sort()) {
  const problems = checkFile(relPath);
  if (problems.length > 0) {
    hasProblems = true;
    console.error(`${relPath}:`);
    for (const p of problems) console.error(p);
  }
}

if (hasProblems) {
  console.error('\nNalezeny mrtvé odkazy na dokumentaci (viz výš).');
  process.exit(1);
} else {
  console.log('Všechny odkazy v dokumentaci vedou na existující soubory a platné kotvy.');
}
