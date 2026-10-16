-- Each order line keeps the product name it was bought under, like the unit price: it is what the
-- customer saw on the checkout page and must not change if the catalog is edited later.
ALTER TABLE order_items ADD COLUMN product_name VARCHAR(200);

-- Lines created before this migration get the product's current name, the best information there is.
UPDATE order_items SET product_name = (SELECT name FROM products WHERE products.id = order_items.product_id);

ALTER TABLE order_items ALTER COLUMN product_name SET NOT NULL;
