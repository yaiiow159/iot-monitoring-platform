-- 245 個新機櫃（CAB-026 ~ CAB-270），三種類型輪流
INSERT INTO cabinet (code, cabinet_type, location, slot_count)
SELECT 'CAB-' || lpad(i::text, 3, '0'),
       (ARRAY['SENSOR','POWER','SERVER'])[(i % 3) + 1],
       '機房 ' || chr(65 + (i % 6)),
       40
FROM generate_series(26, 270) i
ON CONFLICT (code) DO NOTHING;

-- 9800 台裝置 DEV-000200 ~ DEV-009999，每櫃 40 台，機型依機櫃類型可接受的來配
INSERT INTO device (device_id, serial_no, model_code, cabinet_id, slot_no, status)
SELECT 'DEV-' || lpad(n::text, 6, '0'),
       'SN-' || lpad(n::text, 6, '0'),
       CASE c.cabinet_type WHEN 'SENSOR' THEN 'TH-100'
                           WHEN 'POWER' THEN (CASE WHEN n % 2 = 0 THEN 'PWR-3P' ELSE 'CAB-CTRL' END)
                           ELSE (CASE WHEN n % 2 = 0 THEN 'CAB-CTRL' ELSE 'TH-100' END) END,
       c.id,
       ((n - 200) % 40) + 1,
       'UNKNOWN'
FROM generate_series(200, 9999) n
JOIN cabinet c ON c.code = 'CAB-' || lpad((26 + (n - 200) / 40)::text, 3, '0')
ON CONFLICT (device_id) DO NOTHING;
