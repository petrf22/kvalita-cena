import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Doplněk k `i18n.spec.ts`: ten hlídá, že sk/en/pl souhlasí s cs uvnitř bundlu, ale neví nic
 * o tom, jestli kód klíč vůbec volá se správným prefixem — bundle `public/i18n/profile/cs.json`
 * byl kompletní i předtím, než `profile-page.html` volalo `t('title')` místo `t('profile.title')`
 * a Transloco to tiše hlásilo jako "Missing translation" (klíč se po sloučení scope bundlu do
 * jazyka jmenuje `profile.title`, ne `title` — viz `docs/lokalizace.md`).
 *
 * Tenhle test extrahuje literálové klíče z `t('…')`, `translate('…')` a `'…' | transloco`
 * napříč `src/app` a ověří, že každý existuje v odpovídajícím bundlu (kořen `public/i18n/*.json`
 * + `public/i18n/<scope>/*.json` s prefixem názvu scope adresáře — přesně to, co Transloco po
 * `scopes: { keepCasing: true }` v `app.config.ts` slévá do aktivního jazyka). Dynamické klíče
 * (proměnná/index do `enum-labels.ts` mapy) test nevidí a nemusí — ty hlídá `i18n.spec.ts`
 * („enum-labels.ts keys exist in every language bundle"); tady se kontrolují jen tři výskyty
 * template literalu s interpolací (`errors.${…}`, `store.companyId.label.${…}`,
 * `profile.field.${…}`), a to jen na shodu statického prefixu před `${`.
 */

const APP_ROOT = dirname(fileURLToPath(import.meta.url));
const I18N_ROOT = join(APP_ROOT, '..', '..', 'public', 'i18n');
const SOURCE_LANG = 'cs';

type JsonObject = { [key: string]: unknown };

function loadBundle(scopeDir: string, lang: string): JsonObject {
  return JSON.parse(readFileSync(join(scopeDir, `${lang}.json`), 'utf-8')) as JsonObject;
}

function flatten(obj: JsonObject, prefix = ''): Set<string> {
  const result = new Set<string>();
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'object' && value !== null) {
      for (const k of flatten(value as JsonObject, path)) result.add(k);
    } else {
      result.add(path);
    }
  }
  return result;
}

/** Množina klíčů resolvovatelných za běhu: kořen bez prefixu + každý scope s prefixem jeho
 * adresáře (`profile.title`, `price-entry.title`, …), stejně jako je slévá TranslocoService. */
function resolvableKeys(): Set<string> {
  const keys = flatten(loadBundle(I18N_ROOT, SOURCE_LANG));
  const scopeDirs = readdirSync(I18N_ROOT, { withFileTypes: true }).filter((e) => e.isDirectory());
  for (const dir of scopeDirs) {
    const scopeDir = join(I18N_ROOT, dir.name);
    for (const key of flatten(loadBundle(scopeDir, SOURCE_LANG))) {
      keys.add(`${dir.name}.${key}`);
    }
  }
  return keys;
}

function findSourceFiles(dir: string, extensions: string[]): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return findSourceFiles(path, extensions);
    return extensions.some((ext) => entry.name.endsWith(ext)) ? [path] : [];
  });
}

interface FoundKey {
  key: string;
  file: string;
  dynamic: boolean;
}

/** `t('foo.bar')` (direktiva `*transloco="let t; …"`) a `'foo.bar' | transloco` — jen v šablonách.
 * `t('foo.' + expr)` (řetězení místo template literalu, viz `store-detail-page.html`) se bere
 * jako dynamický klíč se statickým prefixem `foo.`, stejně jako template literal v TS. */
function extractFromHtml(content: string, file: string): FoundKey[] {
  const found: FoundKey[] = [];
  for (const m of content.matchAll(/(?<![.\w])t\(\s*'([^']+)'\s*(\+?)/g)) {
    found.push({ key: m[1], file, dynamic: m[2] === '+' });
  }
  for (const m of content.matchAll(/'([^']+)'\s*\|\s*transloco/g)) {
    found.push({ key: m[1], file, dynamic: false });
  }
  return found;
}

/** `translate('foo.bar')` a `` translate(`foo.${x}`) `` — jen v TS (component/service kód). */
function extractFromTs(content: string, file: string): FoundKey[] {
  const found: FoundKey[] = [];
  for (const m of content.matchAll(/\btranslate\(\s*'([^']+)'/g)) {
    found.push({ key: m[1], file, dynamic: false });
  }
  for (const m of content.matchAll(/\btranslate\(\s*`([^`]+)`/g)) {
    const raw = m[1];
    const interpolationIndex = raw.indexOf('${');
    if (interpolationIndex === -1) {
      found.push({ key: raw, file, dynamic: false });
    } else {
      // Jen statický prefix před první interpolací, např. `errors.` z `errors.${errorCode}`.
      found.push({ key: raw.slice(0, interpolationIndex), file, dynamic: true });
    }
  }
  return found;
}

const htmlFiles = findSourceFiles(APP_ROOT, ['.html']);
const tsFiles = findSourceFiles(APP_ROOT, ['.ts']).filter((f) => !f.endsWith('.spec.ts'));

const foundKeys = [
  ...htmlFiles.flatMap((f) => extractFromHtml(readFileSync(f, 'utf-8'), relative(APP_ROOT, f))),
  ...tsFiles.flatMap((f) => extractFromTs(readFileSync(f, 'utf-8'), relative(APP_ROOT, f))),
];

describe('i18n-keys: every translate key used in code resolves in the cs bundle', () => {
  const resolvable = resolvableKeys();

  it.each(foundKeys.map((f) => [`${f.file}: ${f.key}`, f] as const))('%s', (_label, found) => {
    const exists = found.dynamic
      ? [...resolvable].some((k) => k.startsWith(found.key))
      : resolvable.has(found.key);
    expect(exists, `key "${found.key}" (from ${found.file}) not found in any cs bundle`).toBe(true);
  });

  it('found at least one key per known scope (sanity check the extraction itself works)', () => {
    expect(foundKeys.length).toBeGreaterThan(50);
  });
});
