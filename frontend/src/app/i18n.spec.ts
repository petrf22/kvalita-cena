import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import {
  CONFIDENCE_KEYS,
  ERROR_CODE_KEYS,
  EXTERNAL_LINK_KIND_KEYS,
  GEO_SOURCE_KEYS,
  NET_CONTENT_UOM_KEYS,
  PRICE_KIND_KEYS,
  PRODUCT_SORT_KEYS,
  QUANTITY_BASIS_KEYS,
  RECORD_TYPE_KEYS,
  UNIT_BASE_KEYS,
} from './shared/enum-labels';

/**
 * Druhá polovina triku (docs/lokalizace.md): kompilátor hlídá, že klíč z `enum-labels.ts`
 * existuje v kódu (Record<Enum, string> shodí build, jakmile schéma přidá hodnotu bez klíče);
 * tenhle test hlídá, že stejný klíč existuje i ve všech čtyřech jazykových bundlech. Ani jedno
 * samo o sobě nestačí.
 */

const I18N_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'public', 'i18n');
const LANGUAGES = ['cs', 'sk', 'en', 'pl', 'de'] as const;
const SOURCE_LANG = 'cs';

const INTERPOLATION_PATTERN = /\{\{?\s*(\w+)/g;

type JsonObject = { [key: string]: unknown };

function loadBundle(scopeDir: string, lang: string): JsonObject {
  return JSON.parse(readFileSync(join(scopeDir, `${lang}.json`), 'utf-8')) as JsonObject;
}

/** Zploští vnořený bundle na tečkované klíče, ať se sk/en/pl porovnávají proti cs 1:1. */
function flatten(obj: JsonObject, prefix = ''): Map<string, string> {
  const result = new Map<string, string>();
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'object' && value !== null) {
      for (const [k, v] of flatten(value as JsonObject, path)) result.set(k, v);
    } else {
      result.set(path, String(value));
    }
  }
  return result;
}

function interpolationParams(value: string): Set<string> {
  const params = new Set<string>();
  for (const match of value.matchAll(INTERPOLATION_PATTERN)) params.add(match[1]);
  return params;
}

/** Scope adresáře (search/, store/, ...) + kořen (prefix '.', reprezentuje public/i18n/cs.json atd.). */
const scopeDirs = [
  '.',
  ...readdirSync(I18N_ROOT, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => entry.name),
];

describe.each(scopeDirs)('i18n scope %s', (scope) => {
  const scopeDir = join(I18N_ROOT, scope);
  const source = flatten(loadBundle(scopeDir, SOURCE_LANG));

  it.each(LANGUAGES.filter((l) => l !== SOURCE_LANG))('%s has the same keys as cs', (lang) => {
    const translated = flatten(loadBundle(scopeDir, lang));
    expect([...translated.keys()].sort()).toEqual([...source.keys()].sort());
  });

  it.each(LANGUAGES.filter((l) => l !== SOURCE_LANG))(
    '%s has matching interpolation params for every key',
    (lang) => {
      const translated = flatten(loadBundle(scopeDir, lang));
      for (const [key, sourceValue] of source) {
        const translatedValue = translated.get(key);
        if (translatedValue === undefined) continue; // chybějící klíč hlásí předchozí test
        expect(
          [...interpolationParams(translatedValue)].sort(),
          `${scope}/${lang}.json[${key}]`,
        ).toEqual([...interpolationParams(sourceValue)].sort());
      }
    },
  );

  it('has no empty values or values equal to their own key', () => {
    for (const lang of LANGUAGES) {
      const bundle = flatten(loadBundle(scopeDir, lang));
      for (const [key, value] of bundle) {
        expect(value.trim(), `${scope}/${lang}.json[${key}]`).not.toBe('');
        expect(value, `${scope}/${lang}.json[${key}]`).not.toBe(key);
      }
    }
  });
});

describe('enum-labels.ts keys exist in every language bundle', () => {
  const rootBundles = new Map(
    LANGUAGES.map((lang) => [lang, flatten(loadBundle(I18N_ROOT, lang))]),
  );

  const allKeyRecords = {
    PRICE_KIND_KEYS,
    QUANTITY_BASIS_KEYS,
    UNIT_BASE_KEYS,
    PRODUCT_SORT_KEYS,
    CONFIDENCE_KEYS,
    NET_CONTENT_UOM_KEYS,
    EXTERNAL_LINK_KIND_KEYS,
    GEO_SOURCE_KEYS,
    RECORD_TYPE_KEYS,
    ERROR_CODE_KEYS,
  };

  for (const [recordName, record] of Object.entries(allKeyRecords)) {
    const translationKeys = Object.values(record as Record<string, string>);

    it.each(LANGUAGES)(`${recordName}: %s bundle has every referenced key`, (lang) => {
      const bundle = rootBundles.get(lang)!;
      for (const key of translationKeys) {
        expect(bundle.has(key), `missing "${key}" in ${lang}.json`).toBe(true);
      }
    });
  }
});
