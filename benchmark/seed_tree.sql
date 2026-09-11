-- 監控樹的壓測資料：10 個 Equipment → 270 個機櫃 Sensor → 一萬個 Device 葉節點。
-- seed_10k.sql 只補裝置，樹一直是七個節點，所以「祖先鏈查詢貴不貴」量不出來。

BEGIN;

DELETE FROM monitoring_node WHERE name LIKE 'BENCH%';

-- 10 個 Equipment
INSERT INTO monitoring_node (kind, name, parent_id, device_id, sort_order, path)
SELECT 'EQUIPMENT', 'BENCH 廠區 ' || i, NULL, NULL, i * 1000, '0'
FROM generate_series(1, 10) i;
UPDATE monitoring_node SET path = text2ltree(id::text) WHERE name LIKE 'BENCH 廠區%';

-- 270 個機櫃掛在廠區底下
WITH eq AS (SELECT id, row_number() OVER (ORDER BY id) - 1 AS n FROM monitoring_node WHERE name LIKE 'BENCH 廠區%')
INSERT INTO monitoring_node (kind, name, parent_id, device_id, sort_order, path)
SELECT 'SENSOR', 'BENCH 機櫃 ' || c.id, eq.id, NULL, c.id * 1000, '0'
FROM cabinet c JOIN eq ON eq.n = (c.id % 10);
UPDATE monitoring_node child
   SET path = parent.path || text2ltree(child.id::text)
  FROM monitoring_node parent
 WHERE child.parent_id = parent.id AND child.name LIKE 'BENCH 機櫃%';

-- 一萬台裝置各掛一個葉節點，掛在它實際所在的機櫃下
WITH cab AS (
    SELECT n.id AS node_id, split_part(n.name, ' ', 3)::int AS cabinet_id
      FROM monitoring_node n WHERE n.name LIKE 'BENCH 機櫃%'
)
INSERT INTO monitoring_node (kind, name, parent_id, device_id, sort_order, path)
SELECT 'DEVICE', 'BENCH ' || d.device_id, cab.node_id, d.id, d.slot_no * 1000, '0'
FROM device d JOIN cab ON cab.cabinet_id = d.cabinet_id
WHERE d.id NOT IN (SELECT device_id FROM monitoring_node WHERE device_id IS NOT NULL);
UPDATE monitoring_node child
   SET path = parent.path || text2ltree(child.id::text)
  FROM monitoring_node parent
 WHERE child.parent_id = parent.id AND child.name LIKE 'BENCH DEV-%';

COMMIT;

ANALYZE monitoring_node;
SELECT kind, count(*) FROM monitoring_node GROUP BY kind ORDER BY kind;
