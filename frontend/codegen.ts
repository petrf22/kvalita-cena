import type { CodegenConfig } from '@graphql-codegen/cli';

/**
 * Generuje TypeScript typy a typované dotazy z backendového GraphQL schématu — jediný zdroj
 * pravdy kontraktu API (CLAUDE.md). Výstup v `src/app/models/generated/` se commituje, CI ho
 * po `npm run codegen` porovná přes `git diff --exit-code`, aby se schéma a frontend nemohly
 * rozejít nepozorovaně (viz historie duplicitně definovaného PriceKind/MULTIBUY).
 *
 * `documentMode: 'string'` drží dotazy jako obyčejné stringy (žádný DocumentNode) — `graphql`
 * balíček je tak jen build-time závislost, runtime bundle zůstává beze změny (žádné Apollo,
 * jeden POST /graphql přes HttpClient, viz services/graphql-service.ts).
 */
const scalars = {
  ID: 'string',
  Long: 'number',
  // BigDecimal/DateTime/Date — viz backend config/GraphQlScalars.java: BigDecimal
  // serializuje jako JSON číslo, DateTime jako ISO-8601 s pásmem, Date jako ISO den.
  BigDecimal: 'number',
  DateTime: 'string',
  Date: 'string',
};

const config: CodegenConfig = {
  schema: '../backend/src/main/resources/graphql/schema.graphqls',
  documents: ['src/**/*.ts', '!src/app/models/generated/**'],
  generates: {
    'src/app/models/generated/': {
      preset: 'client',
      presetConfig: { fragmentMasking: false },
      config: {
        documentMode: 'string',
        useTypeImports: true,
        skipTypename: true,
        strictScalars: true,
        avoidOptionals: { field: true, object: true },
        scalars,
      },
    },
    // Preset `client` generuje enumy jen jako union typy (žádné `enumsAsConst` — ten
    // parametr do podkladového `typescript-operations` pluginu vůbec neprotéká, je to
    // záměrné omezení presetu). Skutečné konstanty (PriceKind.Multibuy) proto generuje
    // samostatně holý `typescript` plugin s `onlyEnums`, nad stejným schématem.
    'src/app/models/generated/enums.ts': {
      plugins: ['typescript'],
      config: {
        onlyEnums: true,
        enumsAsConst: true,
        scalars,
      },
    },
  },
};

export default config;
