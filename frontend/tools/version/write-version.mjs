#!/usr/bin/env node
// Vygeneruje src/app/version.ts z frontend/package.json — jediné místo pravdy pro verzi appky
// na webu (dřív natvrdo v about-page.ts, nesouhlasilo s package.json). Spouští se automaticky
// přes npm "pre" hooky (prestart/prebuild), výstup se commituje a CI ho ověří stejným
// `git diff --exit-code` jako u graphql-codegen (viz .github/workflows/ci.yml, CLAUDE.md).
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const rootDir = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '..', '..');
const pkg = JSON.parse(readFileSync(path.join(rootDir, 'package.json'), 'utf8'));

const content = `// Generováno tools/version/write-version.mjs z package.json — needituj ručně.
export const APP_VERSION = '${pkg.version}';
`;

writeFileSync(path.join(rootDir, 'src/app/version.ts'), content);
console.log(`src/app/version.ts <- verze ${pkg.version}`);
