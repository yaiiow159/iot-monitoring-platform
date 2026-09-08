-- 使用者、角色與稽核紀錄。
-- 角色只有三種：ADMIN 改設定、OPERATOR 動裝置與樹、VIEWER 只看。
-- 再細的權限矩陣（欄位級、機櫃級）在內部系統裡通常是維護不動的負擔，先不做。

CREATE TABLE app_user (
    id            SERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT app_user_role_valid CHECK (role IN ('ADMIN', 'OPERATOR', 'VIEWER'))
);

-- 誰、什麼時候、對什麼、做了什麼。只增不改：稽核紀錄可以被改就不叫稽核。
CREATE TABLE audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(96)  NOT NULL,
    target_type VARCHAR(32)  NOT NULL,
    target_id   VARCHAR(96),
    outcome     VARCHAR(16)  NOT NULL,
    detail      JSONB,
    CONSTRAINT audit_outcome_valid CHECK (outcome IN ('OK', 'REJECTED', 'DENIED', 'FAILED'))
);
CREATE INDEX idx_audit_at ON audit_log (at DESC);
CREATE INDEX idx_audit_actor ON audit_log (actor, at DESC);
