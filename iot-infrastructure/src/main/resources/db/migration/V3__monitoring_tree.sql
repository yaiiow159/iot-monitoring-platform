-- 監控樹：Equipment → Sensor（無限嵌套）→ Device（葉節點）。
--
-- 路徑用 ltree 物化：「某節點的所有祖先」＝路徑的所有前綴，
-- 「整個子樹」＝一個 <@ 運算子。告警上浮要一路查到根，
-- 用 parent_id 遞迴（WITH RECURSIVE）每次都要跑一趟 CTE，深度越深越慢；
-- ltree 搭配 GiST 索引則是單次索引掃描。

CREATE EXTENSION IF NOT EXISTS ltree;

CREATE TABLE monitoring_node (
    id         SERIAL PRIMARY KEY,
    kind       VARCHAR(16) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    parent_id  INTEGER REFERENCES monitoring_node (id) ON DELETE CASCADE,
    -- DEVICE 節點指向會發遙測的那台裝置；其他種類為 NULL
    device_id  INTEGER REFERENCES device (id) ON DELETE SET NULL,
    -- 同層排序鍵。留間隔（預設 1000），插入中間不必重新編號整層。
    sort_order BIGINT NOT NULL,
    -- 物化路徑，由 id 串成，例如 '1.4.9'。應用層在插入時算好；
    -- 移動節點時要連同整個子樹的 path 一起改寫（本階段不開放移動）。
    path       LTREE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT node_kind_valid CHECK (kind IN ('EQUIPMENT', 'SENSOR', 'DEVICE')),
    -- Equipment 是根且只有它能是根；其他種類一定要有父節點
    CONSTRAINT node_root_is_equipment CHECK (
        (kind = 'EQUIPMENT' AND parent_id IS NULL)
            OR (kind <> 'EQUIPMENT' AND parent_id IS NOT NULL)
    ),
    -- 只有 DEVICE 節點綁裝置，且必須綁：否則同一台裝置會從兩個節點各上浮一次
    CONSTRAINT node_device_binding CHECK (
        (kind = 'DEVICE' AND device_id IS NOT NULL)
            OR (kind <> 'DEVICE' AND device_id IS NULL)
    ),
    -- 一台裝置只能出現在樹上一次
    CONSTRAINT node_device_unique UNIQUE (device_id)
);

-- 子樹與祖先查詢都走這個索引
CREATE INDEX idx_node_path_gist ON monitoring_node USING GIST (path);
-- 「某父節點的子節點，依順序」是最頻繁的查詢；把 id 放進索引就是總序的平手判斷
CREATE INDEX idx_node_siblings ON monitoring_node (parent_id, sort_order, id);

COMMENT ON COLUMN monitoring_node.sort_order IS
    '同層順序。API 一律以 (sort_order, id) 排序，id 是決定性的平手判斷，前端不得自行排序';

-- 「Device 底下不能再有子節點」與「Sensor 只能掛在 Equipment/Sensor 下」
-- 這兩條需要看父節點的 kind，CHECK 做不到，由觸發器把關——
-- 應用層已經擋過一次，這裡是第二道防線，避免直接操作資料庫時把結構寫壞。
CREATE OR REPLACE FUNCTION monitoring_node_enforce_nesting() RETURNS trigger AS $$
DECLARE
    parent_kind VARCHAR(16);
BEGIN
    IF NEW.parent_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT kind INTO parent_kind FROM monitoring_node WHERE id = NEW.parent_id;
    IF parent_kind IS NULL THEN
        RAISE EXCEPTION '父節點不存在：%', NEW.parent_id;
    END IF;
    IF parent_kind = 'DEVICE' THEN
        RAISE EXCEPTION 'Device 是葉節點，底下不能再掛 %（parent=%）', NEW.kind, NEW.parent_id;
    END IF;
    IF NEW.kind = 'DEVICE' AND parent_kind <> 'SENSOR' THEN
        RAISE EXCEPTION 'Device 只能掛在 Sensor 底下（parent kind=%）', parent_kind;
    END IF;
    IF NEW.kind = 'EQUIPMENT' THEN
        RAISE EXCEPTION 'Equipment 是根節點，不能有父節點';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_monitoring_node_nesting
    BEFORE INSERT OR UPDATE OF parent_id, kind ON monitoring_node
    FOR EACH ROW EXECUTE FUNCTION monitoring_node_enforce_nesting();

-- 告警掛到節點上。Device 節點的告警來自它綁定的裝置；Sensor 也可以有自己的告警
-- （例如整個感測群組的通訊中斷）。
ALTER TABLE alarm ADD COLUMN node_id INTEGER REFERENCES monitoring_node (id) ON DELETE SET NULL;
CREATE INDEX idx_alarm_node_firing ON alarm (node_id) WHERE state = 'FIRING';
