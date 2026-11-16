-- Kvalita strojového čtení účtenek z provozních dat (tools/uctenky/README.md, „Měření kvality").
-- Jen čte. Spuštění na serveru:
--   docker compose -f compose.prod.yaml exec -T postgres \
--     sh -c 'psql -U "$POSTGRES_USER" -d kvalitaacena' < ops/sql/receipt-quality.sql
-- nebo z DBeaveru rolí kc_ctenar (vidí core i ai).
--
-- Co čísla znamenají:
--   soucet_sedi      podíl účtenek, kde součet přečtených řádků odpovídá vytištěné částce —
--                    nejtvrdší ukazatel OCR i regexů, nezávislý na tom, co udělal uživatel
--   stroj_trefil     řádek skončil spárovaný se zbožím, které navrhl stroj (kód, naučený název,
--                    podobnost, Open Food Facts)
--   rucne            zboží musel dohledat nebo založit člověk (USER, NEW_PRODUCT, RECEIPT_ITEM)
--   ignorovano       řádek, který uživatel vyřadil (není zboží, nebo se přečetl špatně)
--   nedoreseno       řádek bez zboží na už uzavřené účtence
-- Podíly řádků se počítají jen z UZAVŘENÝCH účtenek (review_status = DONE) — u rozdělané ještě
-- není znát, jak dopadne.
--
-- Skupiny jsou měsíc × OCR model × textový model × profil řetězce. Textový model se zapisuje
-- od 2026-11 (extraction.models); starší účtenky ho mají prázdný. U znovu přečtené účtenky
-- (--requeue) platí poslední zpracování.

WITH job AS (
  SELECT DISTINCT ON (receipt_id)
         receipt_id,
         model                               AS ocr_model,
         extraction -> 'models' ->> 'pretty' AS text_model,
         extraction ->> 'profile'            AS profile
    FROM ai.receipt_job
   WHERE processed_at IS NOT NULL
   ORDER BY receipt_id, processed_at DESC
), line AS (
  SELECT receipt_id,
         count(*)                                                                    AS lines,
         count(*) FILTER (WHERE status = 'MATCHED'
                            AND match_source IN ('CODE', 'LABEL', 'FUZZY', 'OFF'))   AS machine,
         count(*) FILTER (WHERE status = 'MATCHED'
                            AND match_source IN ('USER', 'NEW_PRODUCT', 'RECEIPT_ITEM')) AS manual,
         count(*) FILTER (WHERE status = 'IGNORED')                                  AS ignored,
         count(*) FILTER (WHERE status NOT IN ('MATCHED', 'IGNORED'))                AS unresolved,
         count(*) FILTER (WHERE observation_id IS NOT NULL)                          AS prices
    FROM core.receipt_line
   GROUP BY receipt_id
)
SELECT to_char(date_trunc('month', r.processed_at), 'YYYY-MM')        AS mesic,
       coalesce(j.ocr_model, '?')                                     AS ocr_model,
       coalesce(j.text_model, '-')                                    AS textovy_model,
       coalesce(j.profile, '?')                                       AS profil,
       count(*)                                                       AS uctenek,
       round(100.0 * count(*) FILTER (WHERE r.sum_status = 'MATCH')
             / nullif(count(*) FILTER (WHERE r.sum_status <> 'UNKNOWN'), 0), 1) AS soucet_sedi_pct,
       count(*) FILTER (WHERE r.review_status = 'DONE')               AS uzavrenych,
       sum(l.lines) FILTER (WHERE r.review_status = 'DONE')           AS radku,
       round(100.0 * sum(l.machine) FILTER (WHERE r.review_status = 'DONE')
             / nullif(sum(l.lines) FILTER (WHERE r.review_status = 'DONE'), 0), 1) AS stroj_trefil_pct,
       round(100.0 * sum(l.manual) FILTER (WHERE r.review_status = 'DONE')
             / nullif(sum(l.lines) FILTER (WHERE r.review_status = 'DONE'), 0), 1) AS rucne_pct,
       round(100.0 * sum(l.ignored) FILTER (WHERE r.review_status = 'DONE')
             / nullif(sum(l.lines) FILTER (WHERE r.review_status = 'DONE'), 0), 1) AS ignorovano_pct,
       round(100.0 * sum(l.unresolved) FILTER (WHERE r.review_status = 'DONE')
             / nullif(sum(l.lines) FILTER (WHERE r.review_status = 'DONE'), 0), 1) AS nedoreseno_pct,
       sum(l.prices)                                                  AS zapsanych_cen
  FROM core.receipt r
  JOIN job j ON j.receipt_id = r.id
  LEFT JOIN line l ON l.receipt_id = r.id
 WHERE r.processing_status = 'PROCESSED'
 GROUP BY 1, 2, 3, 4
 ORDER BY 1 DESC, 5 DESC;

-- Odkud se vzal čitelný název řádku (slovník, pravidla, model) — po měsících a textovém modelu.
-- Roste-li podíl modelu, slovník nestačí; klesá-li po doplnění slovníku, doplnění zabralo.
WITH job AS (
  SELECT DISTINCT ON (receipt_id)
         receipt_id, extraction -> 'models' ->> 'pretty' AS text_model
    FROM ai.receipt_job
   WHERE processed_at IS NOT NULL
   ORDER BY receipt_id, processed_at DESC
)
SELECT to_char(date_trunc('month', r.processed_at), 'YYYY-MM') AS mesic,
       coalesce(j.text_model, '-')                             AS textovy_model,
       coalesce(l.label_display_source, '(bez názvu)')         AS zdroj_nazvu,
       count(*)                                                AS radku,
       round(100.0 * count(*) / sum(count(*)) OVER (PARTITION BY date_trunc('month', r.processed_at),
                                                                 j.text_model), 1) AS podil_pct
  FROM core.receipt_line l
  JOIN core.receipt r ON r.id = l.receipt_id
  JOIN job j ON j.receipt_id = r.id
 WHERE r.processing_status = 'PROCESSED'
 GROUP BY date_trunc('month', r.processed_at), j.text_model, l.label_display_source
 ORDER BY 1 DESC, 2, 4 DESC;
