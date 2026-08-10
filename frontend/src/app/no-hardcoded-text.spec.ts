import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Hlídá, že se do šablon nevrátí zadrátovaný český text (docs/lokalizace.md) — regresní pojistka
 * poté, co balík K extrakci dokončil. Kontroluje jen statický text (žádné `{{ }}`/`[binding]`,
 * to je legitimní), ne `.ts` soubory — tam čeština zůstává v komentářích a v endonymech
 * (`settings-page.ts` `LANGUAGE_OPTIONS`), které se schválně nepřekládají.
 */

const SRC_ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CZECH_DIACRITICS = 'ěščřžýáíéúůťďňĚŠČŘŽÝÁÍÉÚŮŤĎŇ';

// Statický text mezi tagy: '>' ... '<', bez '{' (interpolace) uvnitř.
const STATIC_TEXT_NODE = new RegExp(`>([^<{]*[${CZECH_DIACRITICS}][^<]*)<`, 'g');

// Známé atributy nesené jako STATICKÝ literál (ne `[attr]="..."` binding) s českou diakritikou.
const STATIC_ATTRIBUTES = ['placeholder', 'nzPlaceHolder', 'nzTitle', 'nzMessage', 'title', 'alt', 'aria-label'];
const STATIC_ATTRIBUTE_PATTERN = new RegExp(
  `\\s(?:${STATIC_ATTRIBUTES.join('|')})="([^"]*[${CZECH_DIACRITICS}][^"]*)"`,
  'g',
);

function findHtmlFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return findHtmlFiles(path);
    return entry.name.endsWith('.html') ? [path] : [];
  });
}

const htmlFiles = findHtmlFiles(SRC_ROOT);

describe('no-hardcoded-text', () => {
  it.each(htmlFiles.map((path) => [relative(SRC_ROOT, path), path] as const))(
    '%s has no hardcoded Czech static text',
    (_relativePath, path) => {
      const content = readFileSync(path, 'utf-8');
      const textMatches = [...content.matchAll(STATIC_TEXT_NODE)]
        .map((m) => m[1].trim())
        .filter((text) => text.length > 0);
      const attributeMatches = [...content.matchAll(STATIC_ATTRIBUTE_PATTERN)].map((m) => m[1]);

      expect(textMatches, 'static text node(s) with Czech text').toEqual([]);
      expect(attributeMatches, 'static attribute(s) with Czech text').toEqual([]);
    },
  );
});
