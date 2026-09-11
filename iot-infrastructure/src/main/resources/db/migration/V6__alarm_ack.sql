-- 認可不改變 state。把「有人在處理」謊報成 RESOLVED 會讓儀表板的未解除告警數失真，
-- 而那是值班的人唯一會一直盯著的數字。
ALTER TABLE alarm
    ADD COLUMN acknowledged_at TIMESTAMPTZ,
    ADD COLUMN acknowledged_by VARCHAR(64);

-- 告警牆最常問的一句：還在響、而且還沒有人認領的有哪些
CREATE INDEX idx_alarm_unacked ON alarm (fired_at DESC)
    WHERE state = 'FIRING' AND acknowledged_at IS NULL;
