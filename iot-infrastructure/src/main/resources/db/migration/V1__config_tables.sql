-- 設定資料。這些表的列數以萬計而不是以億計，用一般 PostgreSQL 表即可。
--
-- 之所以與時序資料放在同一個資料庫，是因為幾乎每個查詢都要同時用到兩邊：
-- 「列出這個機櫃裡所有裝置過去一小時的平均溫度」需要 join 設定與時序。
-- 分成兩套儲存的話，這個 join 只能在應用層做，等於把資料庫的工作搬到 JVM 裡重做一遍。

CREATE TABLE device_model (
    code           VARCHAR(32) PRIMARY KEY,
    manufacturer   VARCHAR(128) NOT NULL,
    display_name   VARCHAR(128) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE device_model IS '機型：決定一台裝置回報哪些指標';

CREATE TABLE device_model_metric (
    model_code   VARCHAR(32)  NOT NULL REFERENCES device_model (code) ON DELETE CASCADE,
    metric_key   VARCHAR(63)  NOT NULL,
    unit         VARCHAR(16)  NOT NULL,
    min_value    DOUBLE PRECISION NOT NULL,
    max_value    DOUBLE PRECISION NOT NULL,
    ordinal      SMALLINT     NOT NULL,
    PRIMARY KEY (model_code, metric_key),
    CONSTRAINT metric_range_valid CHECK (min_value < max_value)
);

COMMENT ON COLUMN device_model_metric.min_value IS '物理量程下限。超出量程代表感測異常，不是業務上的高低值';

-- 指標代號的數值化字典。
--
-- 時序表每秒新增五萬列，若在每一列存 63 位元組的指標字串，
-- 光是這個欄位兩年就是數 TB。改存 SMALLINT 之後每列省下約 60 位元組，
-- 這是「兩年歷史放得下」的關鍵之一。
CREATE TABLE metric_dictionary (
    metric_id   SMALLSERIAL PRIMARY KEY,
    metric_key  VARCHAR(63) NOT NULL UNIQUE
);

CREATE TABLE cabinet (
    id            SERIAL PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,
    cabinet_type  VARCHAR(32) NOT NULL,
    location      VARCHAR(255),
    slot_count    SMALLINT    NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT slot_count_positive CHECK (slot_count > 0)
);

COMMENT ON COLUMN cabinet.cabinet_type IS 'POWER／SERVER／SENSOR，決定可容納哪些機型';

-- 機櫃類型允許哪些機型。
-- 沒有這張表的話，「配電櫃裡插了一台溫濕度感測器」這種設定錯誤要等到
-- 有人看報表覺得怪怪的才會發現。
CREATE TABLE cabinet_type_model (
    cabinet_type VARCHAR(32) NOT NULL,
    model_code   VARCHAR(32) NOT NULL REFERENCES device_model (code) ON DELETE CASCADE,
    PRIMARY KEY (cabinet_type, model_code)
);

CREATE TABLE device (
    id            SERIAL PRIMARY KEY,
    device_id     VARCHAR(64) NOT NULL UNIQUE,
    serial_no     VARCHAR(64) NOT NULL UNIQUE,
    model_code    VARCHAR(32) NOT NULL REFERENCES device_model (code),
    cabinet_id    INTEGER     REFERENCES cabinet (id) ON DELETE SET NULL,
    slot_no       SMALLINT,
    status        VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_seen_at  TIMESTAMPTZ,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_status_valid CHECK (status IN ('UNKNOWN', 'ONLINE', 'DEGRADED', 'OFFLINE')),
    -- 同一個槽位不能插兩台裝置。這是實體世界的限制，交給資料庫把關才不會被併發繞過。
    CONSTRAINT device_slot_unique UNIQUE (cabinet_id, slot_no)
);

-- 儀表板的預設畫面是「依狀態分組列出裝置」，這兩個索引直接對應該查詢
CREATE INDEX idx_device_status ON device (status);
CREATE INDEX idx_device_cabinet ON device (cabinet_id);
CREATE INDEX idx_device_model ON device (model_code);
-- 斷線掃描：找出最後回報時間早於門檻、且目前仍標記為連線中的裝置
CREATE INDEX idx_device_last_seen ON device (last_seen_at) WHERE status IN ('ONLINE', 'DEGRADED');

CREATE TABLE alarm_rule (
    id               SERIAL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    -- 規則可綁在機型（整批套用）或單一裝置（例外處理），兩者擇一
    model_code       VARCHAR(32) REFERENCES device_model (code) ON DELETE CASCADE,
    device_id        INTEGER     REFERENCES device (id) ON DELETE CASCADE,
    metric_key       VARCHAR(63)  NOT NULL,
    comparison       VARCHAR(16)  NOT NULL,
    threshold        DOUBLE PRECISION NOT NULL,
    secondary_value  DOUBLE PRECISION,
    severity         VARCHAR(16)  NOT NULL,
    duration_seconds INTEGER      NOT NULL DEFAULT 0,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT alarm_rule_scope CHECK (
        (model_code IS NOT NULL AND device_id IS NULL)
            OR (model_code IS NULL AND device_id IS NOT NULL)
    ),
    CONSTRAINT alarm_rule_comparison_valid CHECK (
        comparison IN ('GT', 'GTE', 'LT', 'LTE', 'OUT_OF_RANGE')
    ),
    CONSTRAINT alarm_rule_severity_valid CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT alarm_rule_duration_non_negative CHECK (duration_seconds >= 0),
    -- OUT_OF_RANGE 需要上下界兩個值，其餘比較只用 threshold
    CONSTRAINT alarm_rule_secondary_required CHECK (
        (comparison = 'OUT_OF_RANGE' AND secondary_value IS NOT NULL)
            OR (comparison <> 'OUT_OF_RANGE')
    )
);

CREATE INDEX idx_alarm_rule_model ON alarm_rule (model_code) WHERE enabled;
CREATE INDEX idx_alarm_rule_device ON alarm_rule (device_id) WHERE enabled;

CREATE TABLE alarm (
    id            BIGSERIAL PRIMARY KEY,
    device_id     INTEGER      NOT NULL REFERENCES device (id) ON DELETE CASCADE,
    rule_id       INTEGER      NOT NULL REFERENCES alarm_rule (id) ON DELETE CASCADE,
    severity      VARCHAR(16)  NOT NULL,
    state         VARCHAR(16)  NOT NULL,
    trigger_value DOUBLE PRECISION,
    fired_at      TIMESTAMPTZ  NOT NULL,
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT alarm_state_valid CHECK (state IN ('FIRING', 'RESOLVED')),
    CONSTRAINT alarm_resolved_consistent CHECK (
        (state = 'RESOLVED' AND resolved_at IS NOT NULL)
            OR (state = 'FIRING' AND resolved_at IS NULL)
    )
);

-- 同一條規則對同一台裝置，同時間只能有一則未解除的告警。
-- 沒有這個約束的話，讀數在門檻附近抖動就會刷出成千上萬則重複告警，
-- 而那正是「告警疲勞」的來源——真正的事故會被淹沒在噪音裡。
CREATE UNIQUE INDEX idx_alarm_active_unique
    ON alarm (device_id, rule_id) WHERE state = 'FIRING';

CREATE INDEX idx_alarm_fired_at ON alarm (fired_at DESC);
CREATE INDEX idx_alarm_device_fired ON alarm (device_id, fired_at DESC);
