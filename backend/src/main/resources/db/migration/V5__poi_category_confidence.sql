ALTER TABLE restaurant
    ADD COLUMN category_confidence VARCHAR(16) NOT NULL DEFAULT 'SUPPORTED';

ALTER TABLE restaurant
    ALTER COLUMN category_confidence DROP DEFAULT;
