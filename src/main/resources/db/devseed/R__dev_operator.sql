-- Udviklings-seed — koeres KUN under profilen `dev` (spring.flyway.locations).
-- Aldrig i produktion: der oprettes operatoerer ved provisionering.
--
-- Repeatable migration: koeres igen, hver gang filen aendrer sig. Derfor upsert.
INSERT INTO operator (id, slug, name, hostname)
VALUES ('00000000-0000-4000-8000-000000000001', 'dev', 'Dev Operator', 'localhost')
ON CONFLICT (slug) DO UPDATE
    SET name = EXCLUDED.name, hostname = EXCLUDED.hostname;
