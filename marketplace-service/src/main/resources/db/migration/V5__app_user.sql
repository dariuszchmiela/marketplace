-- Phase 5: registered users.
--
-- shopping_session_id is the owner key the existing tables already use (cart.session_id, orders.session_id).
-- Until Phase 4 the browser chose that UUID and sent it as X-Session-Id; now it is generated once per user on the
-- server, never leaves the server, and is taken from the authenticated principal. Cart and order tables therefore
-- need no migration: their session_id simply means "owner" from now on.
CREATE TABLE app_user
(
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- stored normalized (trimmed, lower case); the constraint makes a non-normalized insert impossible
    email               VARCHAR(254) NOT NULL,
    -- BCrypt hash ("$2a$10$..."), never the password
    password_hash       VARCHAR(100) NOT NULL,
    shopping_session_id UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT uk_app_user_shopping_session UNIQUE (shopping_session_id),
    CONSTRAINT ck_app_user_email_normalized CHECK (email = lower(btrim(email)))
);
