-- FORMA only. Design migration for a NEW, isolated FORMA database.
-- Not executed by CI or against SENTINEL. Requires a separately trusted
-- authentication API. Never connect the Android application directly to DB.
BEGIN;

CREATE SCHEMA IF NOT EXISTS forma;

CREATE TABLE forma.accounts (
    account_id UUID PRIMARY KEY,
    auth_issuer TEXT NOT NULL CHECK (char_length(auth_issuer) BETWEEN 1 AND 512),
    auth_subject TEXT NOT NULL CHECK (char_length(auth_subject) BETWEEN 1 AND 512),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT accounts_provider_identity UNIQUE (auth_issuer, auth_subject)
);

CREATE TABLE forma.profiles (
    account_id UUID NOT NULL REFERENCES forma.accounts(account_id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    profile_document JSONB NOT NULL CHECK (jsonb_typeof(profile_document) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, profile_id)
);

CREATE TABLE forma.workouts (
    account_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    workout_id UUID NOT NULL,
    workout_document JSONB NOT NULL CHECK (jsonb_typeof(workout_document) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, profile_id, workout_id),
    FOREIGN KEY (account_id, profile_id)
      REFERENCES forma.profiles(account_id, profile_id) ON DELETE CASCADE
);

CREATE TABLE forma.measurements (
    account_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    measurement_id UUID NOT NULL,
    measurement_document JSONB NOT NULL CHECK (jsonb_typeof(measurement_document) = 'object'),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, profile_id, measurement_id),
    FOREIGN KEY (account_id, profile_id)
      REFERENCES forma.profiles(account_id, profile_id) ON DELETE CASCADE
);

CREATE TABLE forma.sync_devices (
    account_id UUID NOT NULL REFERENCES forma.accounts(account_id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    PRIMARY KEY (account_id, device_id)
);

CREATE INDEX forma_workouts_changes
    ON forma.workouts (account_id, profile_id, updated_at, workout_id);
CREATE INDEX forma_measurements_changes
    ON forma.measurements (account_id, profile_id, updated_at, measurement_id);

-- This value must ONLY be set in a transaction by an API that has already
-- validated an OIDC token (issuer, audience, expiry, subject, signature).
-- An untrusted client must NEVER receive SQL execution or database credentials.
-- A caller-controlled account_id parameter is NOT an authorization source.
CREATE FUNCTION forma.verified_account_id() RETURNS UUID
    LANGUAGE SQL STABLE AS $$
      SELECT NULLIF(current_setting('forma.verified_account_id', true), '')::uuid
    $$;

ALTER TABLE forma.accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE forma.accounts FORCE ROW LEVEL SECURITY;
ALTER TABLE forma.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE forma.profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE forma.workouts ENABLE ROW LEVEL SECURITY;
ALTER TABLE forma.workouts FORCE ROW LEVEL SECURITY;
ALTER TABLE forma.measurements ENABLE ROW LEVEL SECURITY;
ALTER TABLE forma.measurements FORCE ROW LEVEL SECURITY;
ALTER TABLE forma.sync_devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE forma.sync_devices FORCE ROW LEVEL SECURITY;

CREATE POLICY forma_accounts_scoped ON forma.accounts
    USING (account_id = forma.verified_account_id())
    WITH CHECK (account_id = forma.verified_account_id());
CREATE POLICY forma_profiles_scoped ON forma.profiles
    USING (account_id = forma.verified_account_id())
    WITH CHECK (account_id = forma.verified_account_id());
CREATE POLICY forma_workouts_scoped ON forma.workouts
    USING (account_id = forma.verified_account_id())
    WITH CHECK (account_id = forma.verified_account_id());
CREATE POLICY forma_measurements_scoped ON forma.measurements
    USING (account_id = forma.verified_account_id())
    WITH CHECK (account_id = forma.verified_account_id());
CREATE POLICY forma_devices_scoped ON forma.sync_devices
    USING (account_id = forma.verified_account_id())
    WITH CHECK (account_id = forma.verified_account_id());

-- IMPORTANT: no tables are granted to PUBLIC. Provision a dedicated API
-- login WITHOUT BYPASSRLS and WITHOUT table ownership; use explicit grants
-- only after OIDC middleware, TLS and end-to-end cross-account tests exist.
REVOKE ALL ON SCHEMA forma FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA forma FROM PUBLIC;
COMMIT;
