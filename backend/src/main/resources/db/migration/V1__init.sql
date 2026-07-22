-- Runs under a privileged migration role; the app connects under a DML-only role.

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
    -- Deliberately no IP / user-agent / referrer: counting is analytics, identifiers are tracking.
);

CREATE INDEX idx_clicks_link_bucket ON clicks (link_id, clicked_at);
