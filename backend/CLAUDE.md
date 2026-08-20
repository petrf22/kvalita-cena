# CLAUDE.md — backend

Konvence a příkazy specifické pro `backend/` (Spring Boot 4, Java 25, Gradle Groovy DSL).
Cross-cutting pravidla (jazyk komentářů, licence knihoven, architektura sdílená napříč
aplikacemi) jsou v kořenovém [`CLAUDE.md`](../CLAUDE.md).

## Příkazy

```bash
./gradlew bootRun                    # spustí appku, Boot si přes spring-boot-docker-compose sám nastartuje DB
./gradlew test                       # všechny testy
./gradlew test --tests "*.PriceAggregationServiceTest"   # jeden test
./gradlew clean build
```

Maven ani Gradle nejsou nainstalované globálně — vždy přes `./gradlew`, nikdy `gradle`.

## Konvence

- Gradle **Groovy DSL** — `group = 'cz.kvalitacena'`, package `cz.kvalitacena.*`
- Balíčky: `config`, `controller`, `service`, `security`, `exception`, `db/{entity,repo}`
- Lombok ano, MapStruct ne; konstruktorová injektáž přes `@RequiredArgsConstructor` (odlišně od
  `prani-pani-doktorce`, kde je psaná ručně — u tohoto projektu zvoleno kvůli menší, rychleji
  rostoucí sadě služeb v security/auth vrstvě)
- Liquibase YAML, `db/changelog/<datum>/NNN-nazev.yaml` + master changelog; entity
  `Persistable<Long>`, sloupce `TIMESTAMPTZ` ↔ `OffsetDateTime`
