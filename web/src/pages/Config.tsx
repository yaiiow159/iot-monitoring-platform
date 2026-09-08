import { useMemo, useState } from 'react';
import { api } from '../api';
import type { AlarmSeverity, Comparison, CreateAlarmRuleRequest } from '../api/types';
import { useAsync } from '../hooks/useAsync';
import { CABINET_TYPE_LABEL, COMPARISON_LABEL, SEVERITY_LABEL, formatInt } from '../utils/format';

type Tab = 'models' | 'cabinets' | 'rules';

export function Config() {
  const [tab, setTab] = useState<Tab>('models');

  return (
    <div className="page">
      <div className="tabs">
        <button className={tab === 'models' ? 'tab active' : 'tab'} onClick={() => setTab('models')}>
          機型
        </button>
        <button className={tab === 'cabinets' ? 'tab active' : 'tab'} onClick={() => setTab('cabinets')}>
          機櫃
        </button>
        <button className={tab === 'rules' ? 'tab active' : 'tab'} onClick={() => setTab('rules')}>
          告警規則
        </button>
      </div>

      {tab === 'models' && <ModelsPanel />}
      {tab === 'cabinets' && <CabinetsPanel />}
      {tab === 'rules' && <RulesPanel />}
    </div>
  );
}

function ModelsPanel() {
  const models = useAsync(() => api.listModels(), []);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>機型</h2>
        <span className="sub">機型決定裝置回報哪些指標，也決定告警門檻設得有沒有意義</span>
      </header>
      {models.loading && <p className="empty">載入中…</p>}
      <div className="model-list">
        {(models.data ?? []).map((m) => (
          <article key={m.code} className="model-card">
            <header>
              <span className="mono strong">{m.code}</span>
              <span>{m.displayName}</span>
              <span className="sub">{m.manufacturer}</span>
            </header>
            <table className="data-table compact">
              <thead>
                <tr>
                  <th>指標</th>
                  <th>單位</th>
                  <th className="num">量程下限</th>
                  <th className="num">量程上限</th>
                </tr>
              </thead>
              <tbody>
                {m.metrics.map((metric) => (
                  <tr key={metric.key}>
                    <td className="mono">{metric.key}</td>
                    <td>{metric.unit || '—'}</td>
                    <td className="num mono">{metric.minValue}</td>
                    <td className="num mono">{metric.maxValue}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </article>
        ))}
      </div>
    </section>
  );
}

function CabinetsPanel() {
  const cabinets = useAsync(() => api.listCabinets(), []);
  const [query, setQuery] = useState('');

  const rows = useMemo(() => {
    const all = cabinets.data ?? [];
    const q = query.trim().toLowerCase();
    return q ? all.filter((c) => `${c.id} ${c.name} ${c.location}`.toLowerCase().includes(q)) : all;
  }, [cabinets.data, query]);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>機櫃</h2>
        <div className="panel-tools">
          <input
            className="input"
            placeholder="搜尋機櫃代號、名稱或位置"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <span className="sub">{formatInt(rows.length)} 筆</span>
        </div>
      </header>
      {cabinets.loading && <p className="empty">載入中…</p>}
      <div className="table-scroll tall">
        <table className="data-table">
          <thead>
            <tr>
              <th>代號</th>
              <th>名稱</th>
              <th>類型</th>
              <th>位置</th>
              <th className="num">槽位數</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <tr key={c.id}>
                <td className="mono">{c.id}</td>
                <td>{c.name}</td>
                <td>{CABINET_TYPE_LABEL[c.type] ?? c.type}</td>
                <td className="sub">{c.location}</td>
                <td className="num mono">{c.slotCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

const SEVERITIES: AlarmSeverity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const COMPARISONS: Comparison[] = ['GT', 'GTE', 'LT', 'LTE'];

function RulesPanel() {
  const rules = useAsync(() => api.listAlarmRules(), []);
  const models = useAsync(() => api.listModels(), []);

  const [form, setForm] = useState<CreateAlarmRuleRequest>({
    name: '',
    modelCode: '',
    metric: '',
    comparison: 'GT',
    threshold: 0,
    durationSeconds: 60,
    severity: 'MAJOR',
    enabled: true,
  });
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const selectedModel = (models.data ?? []).find((m) => m.code === form.modelCode) ?? null;
  const selectedMetric = selectedModel?.metrics.find((m) => m.key === form.metric) ?? null;

  /** 門檻落在量程外的規則永遠不會觸發，那是設定錯誤——在送出前就講清楚。 */
  const thresholdWarning =
    selectedMetric && (form.threshold < selectedMetric.minValue || form.threshold > selectedMetric.maxValue)
      ? `門檻超出 ${selectedMetric.key} 的量程（${selectedMetric.minValue} ~ ${selectedMetric.maxValue}），這條規則可能永遠不會觸發`
      : null;

  const canSubmit = form.name.trim() !== '' && form.modelCode !== '' && form.metric !== '' && !submitting;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setMessage(null);
    try {
      const created = await api.createAlarmRule(form);
      setMessage(`已新增規則「${created.name}」（#${created.id}）`);
      setForm({ ...form, name: '', threshold: 0 });
      rules.reload();
    } catch (err) {
      setMessage(err instanceof Error ? `新增失敗：${err.message}` : '新增失敗');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="split">
      <section className="panel">
        <header className="panel-head">
          <h2>告警規則</h2>
          <span className="sub">{formatInt((rules.data ?? []).length)} 條</span>
        </header>
        {rules.loading && <p className="empty">載入中…</p>}
        <div className="table-scroll tall">
          <table className="data-table">
            <thead>
              <tr>
                <th className="num">#</th>
                <th>名稱</th>
                <th>機型</th>
                <th>條件</th>
                <th className="num">持續</th>
                <th>嚴重度</th>
                <th>狀態</th>
              </tr>
            </thead>
            <tbody>
              {(rules.data ?? []).map((r) => (
                <tr key={r.id} className={r.enabled ? undefined : 'disabled-row'}>
                  <td className="num mono">{r.id}</td>
                  <td>{r.name}</td>
                  <td className="mono">{r.modelCode}</td>
                  <td className="mono">
                    {r.metric} {COMPARISON_LABEL[r.comparison]} {r.threshold}
                  </td>
                  <td className="num mono">{r.durationSeconds}s</td>
                  <td>
                    <span className={`sev sev-${r.severity}`}>{SEVERITY_LABEL[r.severity]}</span>
                  </td>
                  <td>{r.enabled ? '啟用' : '停用'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>新增告警規則</h2>
        </header>
        <form className="form" onSubmit={submit}>
          <label className="field">
            <span>規則名稱</span>
            <input
              className="input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="例如：機房高溫"
            />
          </label>

          <label className="field">
            <span>機型</span>
            <select
              className="input"
              value={form.modelCode}
              onChange={(e) => setForm({ ...form, modelCode: e.target.value, metric: '' })}
            >
              <option value="">請選擇</option>
              {(models.data ?? []).map((m) => (
                <option key={m.code} value={m.code}>
                  {m.code}｜{m.displayName}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>指標</span>
            <select
              className="input"
              value={form.metric}
              disabled={!selectedModel}
              onChange={(e) => setForm({ ...form, metric: e.target.value })}
            >
              <option value="">請選擇</option>
              {(selectedModel?.metrics ?? []).map((m) => (
                <option key={m.key} value={m.key}>
                  {m.key}（{m.unit || '無單位'}）
                </option>
              ))}
            </select>
          </label>

          <div className="field-row">
            <label className="field">
              <span>比較</span>
              <select
                className="input"
                value={form.comparison}
                onChange={(e) => setForm({ ...form, comparison: e.target.value as Comparison })}
              >
                {COMPARISONS.map((c) => (
                  <option key={c} value={c}>
                    {COMPARISON_LABEL[c]} {c}
                  </option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>門檻</span>
              <input
                className="input"
                type="number"
                step="any"
                value={form.threshold}
                onChange={(e) => setForm({ ...form, threshold: Number(e.target.value) })}
              />
            </label>

            <label className="field">
              <span>持續秒數</span>
              <input
                className="input"
                type="number"
                min={0}
                value={form.durationSeconds}
                onChange={(e) => setForm({ ...form, durationSeconds: Number(e.target.value) })}
              />
            </label>
          </div>

          {thresholdWarning && <p className="warn-box">{thresholdWarning}</p>}

          <div className="field-row">
            <label className="field">
              <span>嚴重度</span>
              <select
                className="input"
                value={form.severity}
                onChange={(e) => setForm({ ...form, severity: e.target.value as AlarmSeverity })}
              >
                {SEVERITIES.map((s) => (
                  <option key={s} value={s}>
                    {SEVERITY_LABEL[s]}
                  </option>
                ))}
              </select>
            </label>

            <label className="field checkbox">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) => setForm({ ...form, enabled: e.target.checked })}
              />
              <span>建立後立即啟用</span>
            </label>
          </div>

          <button className="btn btn-primary" type="submit" disabled={!canSubmit}>
            {submitting ? '送出中…' : '新增規則'}
          </button>

          {message && <p className="hint">{message}</p>}
        </form>
      </section>
    </div>
  );
}
