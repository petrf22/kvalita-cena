-- dev/seed.sql — ukázková data pro lokální vývoj a ruční testování.
--
-- NENÍ SOUČÁST LIQUIBASE a nesmí se pouštět proti produkci — je to jen pohodlný způsob, jak mít
-- v prázdné DB po `./gradlew bootRun` něco k vyhledání/naskenování. Skript je idempotentní
-- (ON CONFLICT DO NOTHING nad unikátními klíči), takže jde spustit opakovaně bez duplicit.
--
-- Použití (až po prvním startu backendu, aby Liquibase stihla vytvořit schémata):
--   docker compose exec -T postgres psql -U postgres -d kvalitaacena < dev/seed.sql
--
-- Ceny (core.price_observation) se seedem záměrně NEVKLÁDAJÍ — zapisují se přes GraphQL mutaci
-- submitObservation (nebo přes web/mobil UI), aby bylo vidět reálný tok přes agg.recompute_queue
-- a PriceAggregationService. Viz docs/spusteni.md.

BEGIN;

-- Řetězce: dva klasické řetězce + jeden lokální dodavatel (chain_type FARM_SHOP), ať je vidět,
-- že appka nesleduje jen sítě, ale i kvalitu/lokálnost (viz CLAUDE.md).
INSERT INTO core.retail_chain (name, slug, chain_type, country, website) VALUES
  ('Albert', 'albert', 'CHAIN', 'CZ', 'https://www.albert.cz'),
  ('Lidl', 'lidl', 'CHAIN', 'CZ', 'https://www.lidl.cz'),
  ('Farma Sedlák', 'farma-sedlak', 'FARM_SHOP', 'CZ', NULL)
ON CONFLICT (slug) DO NOTHING;

-- Provozovny se skutečnými souřadnicemi (Brno + Praha), aby fungoval geo dotaz nearbyStores
-- (core.store používá GiST index nad ll_to_earth(lat, lon) — viz 02-stores.yaml).
INSERT INTO core.store (chain_id, name, street, city, postal_code, lat, lon)
SELECT c.id, s.name, s.street, s.city, s.postal_code, s.lat, s.lon
FROM (VALUES
  ('albert',       'Albert Brno-Střed',   'Joštova 4',        'Brno',  '60200', 49.19960, 16.60890),
  ('lidl',         'Lidl Brno-Královo Pole', 'Purkyňova 99',  'Brno',  '61200', 49.22730, 16.57530),
  ('farma-sedlak', 'Farma Sedlák Brno',   'Vinohrady 12',     'Brno',  '62100', 49.17800, 16.65100),
  ('albert',       'Albert Praha-Vinohrady', 'Vinohradská 50', 'Praha', '12000', 50.07520, 14.44100),
  ('lidl',         'Lidl Praha-Smíchov',  'Radlická 3',       'Praha', '15000', 50.06710, 14.39840)
) AS s(chain_slug, name, street, city, postal_code, lat, lon)
JOIN core.retail_chain c ON c.slug = s.chain_slug
WHERE NOT EXISTS (
  SELECT 1 FROM core.store existing WHERE existing.name = s.name AND existing.city = s.city
);

-- Kategorie — path je obyčejný text (core.category nepoužívá ltree), jen pro budoucí strom.
INSERT INTO core.category (parent_id, name, slug, path, sort_order) VALUES
  (NULL, 'Potraviny', 'potraviny', '/potraviny', 0)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO core.category (parent_id, name, slug, path, sort_order)
SELECT p.id, s.name, s.slug, s.path, s.sort_order
FROM (VALUES
  ('pecivo', 'Pečivo', 'pecivo', '/potraviny/pecivo', 1),
  ('mlecne', 'Mléčné výrobky', 'mlecne', '/potraviny/mlecne', 2),
  ('zelenina', 'Ovoce a zelenina', 'zelenina', '/potraviny/zelenina', 3)
) AS s(parent_slug, name, slug, path, sort_order)
JOIN core.category p ON p.slug = 'potraviny'
ON CONFLICT (slug) DO NOTHING;

-- Značky.
INSERT INTO core.brand (name, slug) VALUES
  ('Penam', 'penam'),
  ('Madeta', 'madeta'),
  ('Zott', 'zott')
ON CONFLICT (slug) DO NOTHING;

-- Produkty napříč všemi unit_base (COUNT/MASS/VOLUME), aby šlo vyzkoušet výpočet jednotkové
-- ceny (core.price_observation.unit_price je GENERATED STORED z net_content_base).
-- Názvy jsou v základním tvaru kvůli FTS bez lemmatizace (to_tsvector('simple', ...) —
-- plainto_tsquery hledá přesná slova, "másl" nenajde "máslo").
INSERT INTO core.product (name, brand_id, category_id, unit_base, net_content_value, net_content_uom, net_content_base, pieces_in_pack)
SELECT s.name, b.id, cat.id, s.unit_base, s.net_content_value, s.net_content_uom, s.net_content_base, s.pieces_in_pack
FROM (VALUES
  ('Rohlík tukový',       NULL,     'pecivo', 'COUNT',  1::numeric,   'PCS', 1::numeric,     NULL::int),
  ('Chléb konzumní kmínový', 'penam', 'pecivo', 'MASS',  1200::numeric, 'G',  1.2::numeric,   NULL::int),
  ('Máslo čerstvé',       'madeta', 'mlecne', 'MASS',   250::numeric,  'G',  0.25::numeric,  NULL::int),
  ('Mléko polotučné',     'madeta', 'mlecne', 'VOLUME', 1::numeric,    'L',  1::numeric,     NULL::int),
  ('Jogurt bílý',         'zott',   'mlecne', 'MASS',   150::numeric,  'G',  0.15::numeric,  NULL::int),
  ('Vejce M',             NULL,     'mlecne', 'COUNT',  10::numeric,   'PCS', 10::numeric,   10)
) AS s(name, brand_slug, category_slug, unit_base, net_content_value, net_content_uom, net_content_base, pieces_in_pack)
LEFT JOIN core.brand b ON b.slug = s.brand_slug
JOIN core.category cat ON cat.slug = s.category_slug
WHERE NOT EXISTS (SELECT 1 FROM core.product existing WHERE existing.name = s.name);

-- Čárové kódy — EAN-13 se skutečně platnou kontrolní číslicí (jinak by je ZXing v mobilu ani
-- žádný reálný skener neuznal), doplněné nulou zleva na GTIN-14 (stejná normalizace jako
-- ProductGraphQlController.productByCode a mobilní skener), ať jde produkty naskenovat.
INSERT INTO core.product_code (product_id, code, code_type, chain_id, is_primary)
SELECT p.id, ('0' || s.ean13), 'GTIN', NULL, true
FROM (VALUES
  ('Rohlík tukový',          '8594001234561'),
  ('Chléb konzumní kmínový', '8594001234578'),
  ('Máslo čerstvé',          '8594001234585'),
  ('Mléko polotučné',        '8594001234592'),
  ('Jogurt bílý',            '8594001234608'),
  ('Vejce M',                '8594001234615')
) AS s(product_name, ean13)
JOIN core.product p ON p.name = s.product_name
ON CONFLICT (code, code_type, COALESCE(chain_id, 0)) DO NOTHING;

-- Druhové položky bez čárového kódu (core.product.is_generic) — sdílený "koš" pro bezkódový
-- zápis ceny, typicky z podnikové prodejny zemědělského družstva nebo z účtenky bez EANu
-- ("pečivo za 45 Kč"). Rovnou status ACTIVE (na rozdíl od skutečně nově založených položek
-- v appce, které začínají jako DRAFT, viz ProductCatalogService) — je to demonstrační seed dat,
-- ne simulace prvního zápisu. Ceny se udávají za základní jednotku (kg/l/ks), ne za konkrétní
-- balení, protože u bezkódového zboží balení nikdo nezná.
INSERT INTO core.product (name, brand_id, category_id, unit_base, net_content_value, net_content_uom, net_content_base, is_generic)
SELECT s.name, NULL, cat.id, s.unit_base, s.net_content_value, s.net_content_uom, s.net_content_base, true
FROM (VALUES
  ('Chléb konzumní',    'pecivo',   'MASS',  1::numeric, 'KG', 1::numeric),
  ('Rohlík',            'pecivo',   'COUNT', 1::numeric, 'PCS', 1::numeric),
  ('Brambory konzumní', 'zelenina', 'MASS',  1::numeric, 'KG', 1::numeric),
  ('Mléko',             'mlecne',   'VOLUME', 1::numeric, 'L', 1::numeric),
  ('Vejce',             'mlecne',   'COUNT', 1::numeric, 'PCS', 1::numeric)
) AS s(name, category_slug, unit_base, net_content_value, net_content_uom, net_content_base)
JOIN core.category cat ON cat.slug = s.category_slug
WHERE NOT EXISTS (SELECT 1 FROM core.product existing WHERE existing.name = s.name AND existing.is_generic);

-- Historie cen za posledních ~60 dní pro tři dvojice (produkt, obchod), aby šel v UI hned vidět
-- graf vývoje ceny (agg.price_daily). Na rozdíl od zbytku souboru se observace tady vkládají
-- PŘÍMO, ne přes GraphQL mutaci submitObservation — 60 dní historie by přes ruční mutace bylo
-- nepraktické; jednorázový zápis přes UI/API zůstává vyzkoušený jinak (viz komentář nahoře).
-- source = 'IMPORT' odlišuje tahle data od skutečných zápisů a slouží zároveň jako idempotence
-- klíč (WHERE NOT EXISTS níže), aby šel skript spustit opakovaně bez duplicit.
WITH targets AS (
  SELECT p.id AS product_id, p.net_content_base, st.id AS store_id, base.base_price
  FROM (VALUES
    ('Máslo čerstvé',   'Albert Brno-Střed',      42.90),
    ('Máslo čerstvé',   'Lidl Brno-Královo Pole', 39.90),
    ('Mléko polotučné', 'Albert Brno-Střed',      24.90)
  ) AS base(product_name, store_name, base_price)
  JOIN core.product p ON p.name = base.product_name
  JOIN core.store st ON st.name = base.store_name
), days AS (
  SELECT generate_series(0, 60, 4) AS offset_days
), rows AS (
  SELECT
    t.product_id, t.store_id, t.net_content_base,
    -- Mírný trend + malá oscilace, ať je na grafu vidět víc než rovná čára.
    ROUND(t.base_price + (d.offset_days::numeric / 10)
      - (3 * SIN(d.offset_days::double precision / 6.0))::numeric, 2) AS price_amount,
    (now() - (d.offset_days || ' days')::interval) AS observed_at
  FROM targets t CROSS JOIN days d
)
INSERT INTO core.price_observation
  (product_id, store_id, price_amount, price_kind, quantity_basis, net_content_base,
   observed_at, submitter_id, submitter_kind, status, agreement, source)
SELECT r.product_id, r.store_id, r.price_amount, 'REGULAR', 'PACKAGE', r.net_content_base,
       r.observed_at, NULL, 'ANONYMOUS', 'ACTIVE', 'NEUTRAL', 'IMPORT'
FROM rows r
WHERE NOT EXISTS (
  SELECT 1 FROM core.price_observation po
  WHERE po.product_id = r.product_id AND po.store_id = r.store_id
    AND po.observed_at = r.observed_at AND po.source = 'IMPORT'
);

-- Zařadit dotčené buňky do fronty přepočtu — PriceAggregationService.processQueue() (běží každých
-- 5 s) je zpracuje do agg.price_current i agg.price_daily.
INSERT INTO agg.recompute_queue (product_id, store_id, reason)
SELECT DISTINCT po.product_id, po.store_id, 'NEW_OBS'
FROM core.price_observation po
WHERE po.source = 'IMPORT'
  AND NOT EXISTS (
    SELECT 1 FROM agg.recompute_queue q
    WHERE q.product_id = po.product_id AND q.store_id = po.store_id AND q.processed_at IS NULL
  );

-- Pár hodnocení kvality — jen pokud už existuje aspoň jeden uživatel (seed běží typicky i před
-- prvním přihlášením, kdy je auth.app_user prázdná; anonymní hodnocení kvality API nedovolí,
-- vyžaduje přihlášení stejně jako submitObservation ho nevyžaduje).
INSERT INTO core.product_quality_rating (product_id, user_id, grade)
SELECT p.id, u.id, g.grade
FROM (VALUES
  ('Máslo čerstvé', 1),
  ('Mléko polotučné', 2),
  ('Chléb konzumní kmínový', 3)
) AS g(product_name, grade)
JOIN core.product p ON p.name = g.product_name
CROSS JOIN (SELECT id FROM auth.app_user ORDER BY id LIMIT 1) u
ON CONFLICT (product_id, user_id) DO NOTHING;

COMMIT;
