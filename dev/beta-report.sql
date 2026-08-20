-- dev/beta-report.sql — provozní přehled bez analytiky (docs/nasazeni.md, "Provozní přehled
-- bez analytiky"). Appka nemá (a mít nesmí, docs/soukromi.md) žádnou analytiku třetích stran,
-- takže signál "appka se používá/appka se nepoužívá" jde jen přímým dotazem do databáze.
--
-- NENÍ SOUČÁST LIQUIBASE, jen ruční kontrola při běžící uzavřené betě:
--   docker compose exec -T postgres psql -U postgres -d kvalitaacena < dev/beta-report.sql

\echo '== Kolik různých lidí zapsalo cenu za posledních 7 dní =='
SELECT count(DISTINCT submitter_id) AS active_submitters
FROM core.price_observation
WHERE created_at >= now() - interval '7 days' AND submitter_id IS NOT NULL;

\echo '== Zapsané ceny po dnech (posledních 14 dní) =='
SELECT date_trunc('day', created_at)::date AS day, count(*) AS observations
FROM core.price_observation
WHERE created_at >= now() - interval '14 days'
GROUP BY 1
ORDER BY 1 DESC;

\echo '== Zboží/obchody visící v DRAFT/PENDING déle než 3 dny (čekají na potvrzení jinými, docs/reputace.md) =='
SELECT 'product' AS kind, id, name, created_at
FROM core.product
WHERE status = 'DRAFT' AND created_at < now() - interval '3 days'
UNION ALL
SELECT 'store' AS kind, id, name, created_at
FROM core.store
WHERE status = 'PENDING' AND created_at < now() - interval '3 days'
ORDER BY created_at;

\echo '== Nevyřízená nahlášení katalogu/fotek (core.record_flag, docs/reputace.md "Moderace") =='
SELECT record_type, count(DISTINCT (record_type, record_id)) AS flagged_records
FROM core.record_flag
WHERE resolved_at IS NULL
GROUP BY record_type;

\echo '== Nevyřízená zpětná vazba (core.feedback) podle kategorie =='
SELECT category, count(*) AS pending
FROM core.feedback
WHERE handled_at IS NULL
GROUP BY category
ORDER BY pending DESC;

\echo '== Nejstarší nevyřízená zpětná vazba čeká od =='
SELECT min(created_at) AS oldest_pending_feedback
FROM core.feedback
WHERE handled_at IS NULL;
