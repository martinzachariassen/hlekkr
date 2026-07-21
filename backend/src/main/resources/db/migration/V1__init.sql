-- Core schema for the URL shortener.
-- Runs under a privileged migration role at deploy time; the application connects
-- under a least-privilege role that only has DML on these two tables (see README §Database hardening).

CREATE TABLE links (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(16) UNIQUE NOT NULL,
    target_url TEXT NOT NULL,
    owner_token_hash CHAR(64) NOT NULL,      -- SHA-256 hex of the owner token; raw token never stored
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ                    -- soft delete
);

-- Partial index: redirect lookups only ever query live links.
CREATE INDEX idx_links_code ON links (code) WHERE deleted_at IS NULL;

CREATE TABLE clicks (
    id BIGSERIAL PRIMARY KEY,
    link_id BIGINT NOT NULL REFERENCES links (id),
    clicked_at TIMESTAMPTZ NOT NULL DEFAULT now()
    -- Intentionally stores no IP / user-agent / referrer: counting is analytics,
    -- collecting identifiers would make this a tracking product (see README).
);

CREATE INDEX idx_clicks_link_bucket ON clicks (link_id, clicked_at);
