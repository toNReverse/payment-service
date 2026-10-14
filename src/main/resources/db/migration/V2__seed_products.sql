-- The Christmas catalog, in EUR cents. The limoncello panettone is no longer sold: it stays in the
-- table (old orders reference it) but is inactive, so GET /products does not list it.
INSERT INTO products (id, name, price_minor, currency, active) VALUES
    ('panettone-classico',   'Panettone Classico 1 kg',      2800, 'EUR', TRUE),
    ('panettone-cioccolato', 'Panettone al Cioccolato 1 kg', 3200, 'EUR', TRUE),
    ('panettone-pistacchio', 'Panettone al Pistacchio 1 kg', 3800, 'EUR', TRUE),
    ('pandoro',              'Pandoro 1 kg',                 2600, 'EUR', TRUE),
    ('panettone-limoncello', 'Panettone al Limoncello 1 kg', 3500, 'EUR', FALSE);
