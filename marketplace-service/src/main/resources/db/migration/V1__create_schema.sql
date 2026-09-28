CREATE TABLE product
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               VARCHAR(200)   NOT NULL,
    description        TEXT           NOT NULL,
    price              NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    available_quantity INTEGER        NOT NULL CHECK (available_quantity >= 0),
    version            BIGINT         NOT NULL DEFAULT 0
);

-- A cart belongs to an anonymous session for now (X-Session-Id header).
CREATE TABLE cart
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE
);

-- product_id intentionally has no foreign key: the cart is not the source of truth
-- for products, and checkout re-validates that every product still exists.
CREATE TABLE cart_item
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id    BIGINT  NOT NULL REFERENCES cart (id) ON DELETE CASCADE,
    product_id BIGINT  NOT NULL,
    quantity   INTEGER NOT NULL CHECK (quantity > 0),
    CONSTRAINT uk_cart_item_cart_product UNIQUE (cart_id, product_id)
);

-- "order" is a reserved word in SQL, hence the plural table name.
CREATE TABLE orders
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id UUID           NOT NULL,
    status     VARCHAR(32)    NOT NULL,
    total      NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
    created_at TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_orders_session_id ON orders (session_id);

-- Order lines are a historical snapshot: name and price are copied at checkout time,
-- so product_id is kept for reference only (no foreign key).
CREATE TABLE order_line
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id     BIGINT         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   BIGINT         NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    quantity     INTEGER        NOT NULL CHECK (quantity > 0),
    line_total   NUMERIC(12, 2) NOT NULL CHECK (line_total >= 0)
);

CREATE INDEX idx_order_line_order_id ON order_line (order_id);
