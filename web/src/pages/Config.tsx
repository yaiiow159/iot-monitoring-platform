import { useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type {
  AlarmSeverity,
  AuditEntry,
  CabinetType,
  Comparison,
  CreateAlarmRuleRequest,
  CreateUserRequest,
  Device,
  MetricDefinition,
  RegisterDeviceRequest,
  Role,
} from '../api/types';
import { can, ROLE_LABEL, useSession } from '../auth/session';
import { useAsync } from '../hooks/useAsync';
import {
  CABINET_TYPE_LABEL,
  COMPARISON_LABEL,
  SEVERITY_LABEL,
  STATUS_LABEL,
  formatInt,
  formatRelative,
  formatTime,
} from '../utils/format';

/*
 * 設定中心。每個分頁都是「左邊清單、右邊表單」。
 * 表單只做最基本的格式檢查；業務規則（槽位有沒有被占、門檻在不在量程內、機櫃收不收這個機型）
 * 全部交給後端的領域層判斷，表單原文顯示後端的拒絕原因——那些原因才是這個系統的知識所在。
 */

type Tab = 'models' | 'cabinets' | 'devices' | 'rules' | 'users' | 'audit';

const TABS: { key: Tab; label: string; adminOnly?: boolean }[] = [
  { key: 'models', label: '機型' },
  { key: 'cabinets', label: '機櫃' },
  { key: 'devices', label: '裝置' },
  { key: 'rules', label: '告警規則' },
  { key: 'users', label: '使用者', adminOnly: true },
  { key: 'audit', label: '稽核紀錄', adminOnly: true },
];

export function Config() {
  const [tab, setTab] = useState<Tab>('models');
  const session = useSession();
  const admin = can(session?.user, 'configure');

  return (
    <div className="page">
      <div className="tabs">
        {TABS.filter((t) => !t.adminOnly || admin).map((t) => (
          <button key={t.key} className={tab === t.key ? 'tab active' : 'tab'} onClick={() => setTab(t.key)}>
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'models' && <ModelsPanel />}
      {tab === 'cabinets' && <CabinetsPanel />}
      {tab === 'devices' && <DevicesPanel />}
      {tab === 'rules' && <RulesPanel />}
      {tab === 'users' && admin && <UsersPanel />}
      {tab === 'audit' && admin && <AuditPanel />}
    </div>
  );
}

// ---------------------------------------------------------------- 共用

/** 送出狀態與結果訊息。成功與失敗都顯示原文，失敗的原文來自後端的領域規則。 */
function useSubmit<T>(action: () => Promise<T>, onSuccess: (result: T) => string) {
  const [busy, setBusy] = useState(false);
  const [ok, setOk] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setOk(null);
    setError(null);
    try {
      setOk(onSuccess(await action()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }
  return { busy, ok, error, submit };
}

function Feedback({ ok, error }: { ok: string | null; error: string | null }) {
  return (
    <>
      {error && (
        <p className="error" role="alert">
          <span className="caption">後端拒絕</span>
          {error}
        </p>
      )}
      {ok && <p className="ok-box">{ok}</p>}
    </>
  );
}

/** 沒有權限就不畫表單，只留一句話說明誰能做；後端仍會再擋一次。 */
function Gate({ action, children }: { action: 'configure' | 'operate'; children: ReactNode }) {
  const session = useSession();
  if (can(session?.user, action)) return <>{children}</>;
  const who = action === 'configure' ? '管理員' : '管理員或值班工程師';
  return (
    <p className="empty">
      你的角色是「{session ? ROLE_LABEL[session.user.role] : '未登入'}」，這個操作需要{who}。
    </p>
  );
}

// ---------------------------------------------------------------- 機型

function ModelsPanel() {
  const models = useAsync(() => api.listModels(), []);

  return (
    <div className="split">
      <section className="panel">
        <header className="panel-head">
          <h2>機型</h2>
          <span className="sub">機型決定裝置回報哪些指標，也決定告警門檻設得有沒有意義</span>
        </header>
        {models.loading && <p className="empty">載入中…</p>}
        {models.error && <p className="error">{models.error}</p>}
        <div className="model-list">
          {(models.data ?? []).map((m) => (
            <article key={m.code} className="model-card">
              <header>
                <span className="model-code">{m.code}</span>
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

      <section className="panel">
        <header className="panel-head">
          <h2>新增機型</h2>
        </header>
        <Gate action="configure">
          <ModelForm onCreated={models.reload} />
        </Gate>
      </section>
    </div>
  );
}

const EMPTY_METRIC: MetricDefinition = { key: '', unit: '', minValue: 0, maxValue: 100 };

function ModelForm({ onCreated }: { onCreated(): void }) {
  const [code, setCode] = useState('');
  const [manufacturer, setManufacturer] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [metrics, setMetrics] = useState<MetricDefinition[]>([{ ...EMPTY_METRIC }]);

  const { busy, ok, error, submit } = useSubmit(
    () => api.createModel({ code: code.trim().toUpperCase(), manufacturer, displayName, metrics }),
    (m) => {
      onCreated();
      setCode('');
      setDisplayName('');
      setMetrics([{ ...EMPTY_METRIC }]);
      return `已新增機型 ${m.code}，${m.metrics.length} 個指標`;
    },
  );

  const update = (i: number, patch: Partial<MetricDefinition>) =>
    setMetrics((prev) => prev.map((m, j) => (j === i ? { ...m, ...patch } : m)));

  /** 量程反過來、指標代號重複，這兩種後端一定會拒絕，先在畫面上提醒 */
  const localWarning = useMemo(() => {
    const keys = metrics.map((m) => m.key.trim()).filter(Boolean);
    if (new Set(keys).size !== keys.length) return '指標代號重複';
    if (metrics.some((m) => m.minValue >= m.maxValue)) return '量程下限必須小於上限';
    return null;
  }, [metrics]);

  const canSubmit = code.trim() !== '' && displayName.trim() !== '' && metrics.some((m) => m.key.trim()) && !busy;

  return (
    <form className="form" onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span>代號</span>
          <input className="input mono" value={code} onChange={(e) => setCode(e.target.value)} placeholder="TH-200" />
        </label>
        <label className="field">
          <span>製造商</span>
          <input className="input" value={manufacturer} onChange={(e) => setManufacturer(e.target.value)} />
        </label>
        <label className="field">
          <span>顯示名稱</span>
          <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </label>
      </div>

      <div className="metric-editor">
        <div className="metric-editor-head">
          <span className="caption">指標</span>
          <button type="button" className="btn" onClick={() => setMetrics((p) => [...p, { ...EMPTY_METRIC }])}>
            ＋ 指標
          </button>
        </div>
        {metrics.map((m, i) => (
          <div key={i} className="metric-row">
            <input
              className="input mono"
              placeholder="temperature"
              value={m.key}
              onChange={(e) => update(i, { key: e.target.value })}
            />
            <input
              className="input"
              placeholder="單位（可空）"
              value={m.unit}
              onChange={(e) => update(i, { unit: e.target.value })}
            />
            <input
              className="input"
              type="number"
              step="any"
              value={m.minValue}
              onChange={(e) => update(i, { minValue: Number(e.target.value) })}
            />
            <input
              className="input"
              type="number"
              step="any"
              value={m.maxValue}
              onChange={(e) => update(i, { maxValue: Number(e.target.value) })}
            />
            <button
              type="button"
              className="btn btn-icon"
              aria-label="移除指標"
              disabled={metrics.length === 1}
              onClick={() => setMetrics((p) => p.filter((_, j) => j !== i))}
            >
              ×
            </button>
          </div>
        ))}
        <p className="hint">代號、單位、量程下限、量程上限。門檻超出量程的告警規則之後會被拒絕，量程請照規格書填。</p>
      </div>

      {localWarning && <p className="warn-box">{localWarning}</p>}
      <button className="btn btn-primary" type="submit" disabled={!canSubmit || localWarning !== null}>
        {busy ? '送出中…' : '新增機型'}
      </button>
      <Feedback ok={ok} error={error} />
    </form>
  );
}

// ---------------------------------------------------------------- 機櫃

const CABINET_TYPES: CabinetType[] = ['POWER', 'SERVER', 'SENSOR'];

function CabinetsPanel() {
  const cabinets = useAsync(() => api.listCabinets(), []);
  const [query, setQuery] = useState('');

  const rows = useMemo(() => {
    const all = cabinets.data ?? [];
    const q = query.trim().toLowerCase();
    return q ? all.filter((c) => `${c.id} ${c.name} ${c.location}`.toLowerCase().includes(q)) : all;
  }, [cabinets.data, query]);

  return (
    <div className="split">
      <section className="panel">
        <header className="panel-head">
          <h2>機櫃</h2>
          <div className="panel-tools">
            <input
              className="input"
              placeholder="搜尋代號或位置"
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
                <th>類型</th>
                <th>位置</th>
                <th className="num">槽位數</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id}>
                  <td className="mono">{c.id}</td>
                  <td>{CABINET_TYPE_LABEL[c.type] ?? c.type}</td>
                  <td className="sub">{c.location}</td>
                  <td className="num mono">{c.slotCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>新增機櫃</h2>
        </header>
        <Gate action="configure">
          <CabinetForm onCreated={cabinets.reload} />
        </Gate>
      </section>
    </div>
  );
}

function CabinetForm({ onCreated }: { onCreated(): void }) {
  const [name, setName] = useState('');
  const [type, setType] = useState<CabinetType>('SENSOR');
  const [location, setLocation] = useState('');
  const [slotCount, setSlotCount] = useState(40);

  const { busy, ok, error, submit } = useSubmit(
    () => api.createCabinet({ name: name.trim().toUpperCase(), type, location, slotCount }),
    (c) => {
      onCreated();
      setName('');
      return `已新增機櫃 ${c.id}（${CABINET_TYPE_LABEL[c.type]}，${c.slotCount} 槽）`;
    },
  );

  return (
    <form className="form" onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span>代號</span>
          <input className="input mono" value={name} onChange={(e) => setName(e.target.value)} placeholder="CAB-026" />
        </label>
        <label className="field">
          <span>類型</span>
          <select className="input" value={type} onChange={(e) => setType(e.target.value as CabinetType)}>
            {CABINET_TYPES.map((t) => (
              <option key={t} value={t}>
                {CABINET_TYPE_LABEL[t]}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>位置</span>
          <input className="input" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="機房 A" />
        </label>
        <label className="field">
          <span>槽位數</span>
          <input
            className="input"
            type="number"
            min={1}
            value={slotCount}
            onChange={(e) => setSlotCount(Number(e.target.value))}
          />
        </label>
      </div>
      <p className="hint">機櫃類型決定收哪些機型：配電櫃收電力模組、感測櫃收感測器。裝置註冊時後端會依此拒絕。</p>
      <button className="btn btn-primary" type="submit" disabled={busy || !name.trim() || !location.trim()}>
        {busy ? '送出中…' : '新增機櫃'}
      </button>
      <Feedback ok={ok} error={error} />
    </form>
  );
}

// ---------------------------------------------------------------- 裝置

function DevicesPanel() {
  const cabinets = useAsync(() => api.listCabinets(), []);
  const models = useAsync(() => api.listModels(), []);
  const [cabinetId, setCabinetId] = useState('');
  // 一萬台裝置不帶篩選會撞到後端 1000 筆上限，所以先選機櫃再列
  const devices = useAsync(
    () => (cabinetId ? api.listDevices({ cabinetId }) : Promise.resolve([] as Device[])),
    [cabinetId],
  );

  return (
    <div className="split">
      <section className="panel">
        <header className="panel-head">
          <h2>裝置</h2>
          <div className="panel-tools">
            <select className="input" value={cabinetId} onChange={(e) => setCabinetId(e.target.value)}>
              <option value="">選擇機櫃</option>
              {(cabinets.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.id}｜{CABINET_TYPE_LABEL[c.type]}｜{c.location}
                </option>
              ))}
            </select>
            <span className="sub">{formatInt((devices.data ?? []).length)} 台</span>
          </div>
        </header>
        {!cabinetId ? (
          <p className="empty">先選一個機櫃。裝置清單依機櫃分頁，一萬台裝置不會一次載入。</p>
        ) : devices.loading ? (
          <p className="empty">載入中…</p>
        ) : (
          <div className="table-scroll tall">
            <table className="data-table">
              <thead>
                <tr>
                  <th className="num">槽位</th>
                  <th>裝置</th>
                  <th>序號</th>
                  <th>機型</th>
                  <th>狀態</th>
                  <th>最後回報</th>
                </tr>
              </thead>
              <tbody>
                {(devices.data ?? []).map((d) => (
                  <tr key={d.deviceId}>
                    <td className="num mono">{d.slot}</td>
                    <td className="mono">
                      <Link to={`/devices/${d.deviceId}`}>{d.deviceId}</Link>
                    </td>
                    <td className="mono sub">{d.name}</td>
                    <td className="mono">{d.modelCode}</td>
                    <td>
                      <span className={`status-pill status-${d.status}`}>{STATUS_LABEL[d.status]}</span>
                    </td>
                    <td className="sub">{d.lastSeenAt ? formatRelative(d.lastSeenAt) : '從未'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>註冊裝置</h2>
          <span className="sub">未註冊的裝置送來的遙測會被丟棄</span>
        </header>
        <Gate action="operate">
          <DeviceForm
            models={(models.data ?? []).map((m) => m.code)}
            cabinets={(cabinets.data ?? []).map((c) => ({ id: c.id, type: c.type, slotCount: c.slotCount }))}
            defaultCabinet={cabinetId}
            onCreated={(d) => {
              if (d.cabinetId === cabinetId) devices.reload();
            }}
          />
        </Gate>
      </section>
    </div>
  );
}

interface DeviceFormProps {
  models: string[];
  cabinets: { id: string; type: CabinetType; slotCount: number }[];
  defaultCabinet: string;
  onCreated(device: Device): void;
}

function DeviceForm({ models, cabinets, defaultCabinet, onCreated }: DeviceFormProps) {
  const [form, setForm] = useState<RegisterDeviceRequest>({ deviceId: '', name: '', modelCode: '', cabinetId: '', slot: 1 });
  const cabinetId = form.cabinetId || defaultCabinet;
  const cabinet = cabinets.find((c) => c.id === cabinetId);

  const { busy, ok, error, submit } = useSubmit(
    () =>
      api.registerDevice({
        deviceId: form.deviceId.trim().toUpperCase(),
        name: form.name?.trim() || undefined,
        modelCode: form.modelCode,
        cabinetId: cabinetId || undefined,
        slot: cabinetId ? form.slot : undefined,
      }),
    (d) => {
      onCreated(d);
      setForm((f) => ({ ...f, deviceId: '', name: '', slot: (f.slot ?? 0) + 1 }));
      return `已註冊 ${d.deviceId}（${d.modelCode}${d.cabinetId ? `，${d.cabinetId} 槽位 ${d.slot}` : ''}），現在可以收遙測了`;
    },
  );

  return (
    <form className="form" onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span>裝置代號</span>
          <input
            className="input mono"
            value={form.deviceId}
            onChange={(e) => setForm({ ...form, deviceId: e.target.value })}
            placeholder="DEV-000201"
          />
        </label>
        <label className="field">
          <span>序號（可空）</span>
          <input className="input mono" value={form.name ?? ''} onChange={(e) => setForm({ ...form, name: e.target.value })} />
        </label>
      </div>
      <div className="field-row">
        <label className="field">
          <span>機型</span>
          <select className="input" value={form.modelCode} onChange={(e) => setForm({ ...form, modelCode: e.target.value })}>
            <option value="">請選擇</option>
            {models.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>機櫃</span>
          <select className="input" value={cabinetId} onChange={(e) => setForm({ ...form, cabinetId: e.target.value })}>
            <option value="">不入櫃</option>
            {cabinets.map((c) => (
              <option key={c.id} value={c.id}>
                {c.id}｜{CABINET_TYPE_LABEL[c.type]}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>槽位{cabinet ? `（1~${cabinet.slotCount}）` : ''}</span>
          <input
            className="input"
            type="number"
            min={1}
            max={cabinet?.slotCount}
            disabled={!cabinetId}
            value={form.slot ?? 1}
            onChange={(e) => setForm({ ...form, slot: Number(e.target.value) })}
          />
        </label>
      </div>
      <p className="hint">
        後端會檢查三件事並原文回覆：機櫃類型收不收這個機型、槽位在不在範圍內、槽位有沒有被占。
      </p>
      <button className="btn btn-primary" type="submit" disabled={busy || !form.deviceId.trim() || !form.modelCode}>
        {busy ? '送出中…' : '註冊裝置'}
      </button>
      <Feedback ok={ok} error={error} />
    </form>
  );
}

// ---------------------------------------------------------------- 告警規則

const SEVERITIES: AlarmSeverity[] = ['INFO', 'WARNING', 'CRITICAL'];
const COMPARISONS: Comparison[] = ['GT', 'GTE', 'LT', 'LTE', 'OUT_OF_RANGE'];

/** OUT_OF_RANGE 是區間，顯示成「metric ∉ [a, b]」比「metric ∉ a」誠實。 */
function describeCondition(rule: {
  metric: string;
  comparison: Comparison;
  threshold: number;
  secondaryValue?: number | null;
}): string {
  if (rule.comparison === 'OUT_OF_RANGE' && rule.secondaryValue != null) {
    const lo = Math.min(rule.threshold, rule.secondaryValue);
    const hi = Math.max(rule.threshold, rule.secondaryValue);
    return `${rule.metric} ${COMPARISON_LABEL.OUT_OF_RANGE} [${lo}, ${hi}]`;
  }
  return `${rule.metric} ${COMPARISON_LABEL[rule.comparison]} ${rule.threshold}`;
}

function RulesPanel() {
  const rules = useAsync(() => api.listAlarmRules(), []);
  const models = useAsync(() => api.listModels(), []);

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
                <th>範圍</th>
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
                  <td className="mono">
                    {r.deviceId ? (
                      <span className="scope scope-device" title="只套用到這台裝置，同指標的機型規則對它失效">
                        裝置 {r.deviceId}
                      </span>
                    ) : (
                      <span className="scope scope-model">機型 {r.modelCode}</span>
                    )}
                  </td>
                  <td className="mono">{describeCondition(r)}</td>
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
        <p className="hint">裝置規則以指標為單位整個取代機型規則：某台裝置在 temperature 上有自己的規則，機型的 temperature 規則對它就全部失效（ADR-0006）。</p>
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>新增告警規則</h2>
        </header>
        <Gate action="configure">
          <RuleForm models={models.data ?? []} onCreated={rules.reload} />
        </Gate>
      </section>
    </div>
  );
}

function RuleForm({ models, onCreated }: { models: { code: string; displayName: string; metrics: MetricDefinition[] }[]; onCreated(): void }) {
  const [scope, setScope] = useState<'model' | 'device'>('model');
  const [form, setForm] = useState<CreateAlarmRuleRequest>({
    name: '',
    modelCode: '',
    deviceId: '',
    metric: '',
    comparison: 'GT',
    threshold: 0,
    secondaryValue: null,
    durationSeconds: 60,
    severity: 'WARNING',
    enabled: true,
  });

  // 裝置規則的指標來自那台裝置的機型：輸入裝置代號後查一次，量程提醒才有依據
  const deviceCode = scope === 'device' ? (form.deviceId ?? '').trim().toUpperCase() : '';
  const device = useAsync(
    () => (deviceCode.length >= 6 ? api.getDevice(deviceCode) : Promise.resolve(null)),
    [deviceCode],
  );
  const effectiveModel = scope === 'model' ? form.modelCode : device.data?.modelCode ?? '';
  const selectedModel = models.find((m) => m.code === effectiveModel) ?? null;
  const selectedMetric = selectedModel?.metrics.find((m) => m.key === form.metric) ?? null;
  const needsSecondary = form.comparison === 'OUT_OF_RANGE';

  /** 門檻落在量程外的規則永遠不會觸發，那是設定錯誤——在送出前就講清楚。 */
  const outOfScale = (v: number) =>
    selectedMetric !== null && (v < selectedMetric.minValue || v > selectedMetric.maxValue);
  const thresholdWarning =
    selectedMetric && (outOfScale(form.threshold) || (needsSecondary && outOfScale(form.secondaryValue ?? 0)))
      ? `門檻超出 ${selectedMetric.key} 的量程（${selectedMetric.minValue} ~ ${selectedMetric.maxValue}），後端會拒絕`
      : null;

  const canSubmit =
    form.name.trim() !== '' &&
    (scope === 'model' ? !!form.modelCode : !!deviceCode) &&
    form.metric !== '' &&
    (!needsSecondary || form.secondaryValue != null);

  const { busy, ok, error, submit } = useSubmit(
    () =>
      api.createAlarmRule({
        ...form,
        modelCode: scope === 'model' ? form.modelCode : null,
        deviceId: scope === 'device' ? deviceCode : null,
        // 非區間比較不該帶 secondaryValue，後端會把多餘的值當成設定錯誤。
        secondaryValue: needsSecondary ? form.secondaryValue : null,
      }),
    (created) => {
      onCreated();
      setForm({ ...form, name: '', threshold: 0, secondaryValue: null });
      return created.deviceId
        ? `已新增裝置規則「${created.name}」（#${created.id}），${created.deviceId} 在 ${created.metric} 上的機型規則已被取代`
        : `已新增規則「${created.name}」（#${created.id}）`;
    },
  );

  return (
    <form className="form" onSubmit={submit}>
      <div className="seg" role="radiogroup" aria-label="規則範圍">
        <button type="button" className={scope === 'model' ? 'seg-item active' : 'seg-item'} onClick={() => { setScope('model'); setForm({ ...form, metric: '' }); }}>
          套用整個機型
        </button>
        <button type="button" className={scope === 'device' ? 'seg-item active' : 'seg-item'} onClick={() => { setScope('device'); setForm({ ...form, metric: '' }); }}>
          單一裝置例外
        </button>
      </div>

      <label className="field">
        <span>規則名稱</span>
        <input
          className="input"
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
          placeholder={scope === 'model' ? '例如：機房高溫' : '例如：冷藏區溫濕度計放寬'}
        />
      </label>

      {scope === 'model' ? (
        <label className="field">
          <span>機型</span>
          <select
            className="input"
            value={form.modelCode ?? ''}
            onChange={(e) => setForm({ ...form, modelCode: e.target.value, metric: '' })}
          >
            <option value="">請選擇</option>
            {models.map((m) => (
              <option key={m.code} value={m.code}>
                {m.code}｜{m.displayName}
              </option>
            ))}
          </select>
        </label>
      ) : (
        <label className="field">
          <span>裝置代號</span>
          <input
            className="input mono"
            value={form.deviceId ?? ''}
            onChange={(e) => setForm({ ...form, deviceId: e.target.value, metric: '' })}
            placeholder="DEV-000001"
          />
          <span className="field-note">
            {deviceCode.length < 6
              ? '輸入裝置代號後會查出它的機型'
              : device.loading
                ? '查詢中…'
                : device.data
                  ? `機型 ${device.data.modelCode}，${device.data.cabinetId || '未入櫃'}`
                  : '找不到這台裝置'}
          </span>
        </label>
      )}

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
                {c === 'OUT_OF_RANGE' ? `${COMPARISON_LABEL[c]} 區間外` : `${COMPARISON_LABEL[c]} ${c}`}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>{needsSecondary ? '下界' : '門檻'}</span>
          <input
            className="input"
            type="number"
            step="any"
            value={form.threshold}
            onChange={(e) => setForm({ ...form, threshold: Number(e.target.value) })}
          />
        </label>

        {needsSecondary && (
          <label className="field">
            <span>上界</span>
            <input
              className="input"
              type="number"
              step="any"
              value={form.secondaryValue ?? ''}
              onChange={(e) =>
                setForm({ ...form, secondaryValue: e.target.value === '' ? null : Number(e.target.value) })
              }
            />
          </label>
        )}

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
          <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
          <span>建立後立即啟用</span>
        </label>
      </div>

      <button className="btn btn-primary" type="submit" disabled={!canSubmit || busy}>
        {busy ? '送出中…' : scope === 'model' ? '新增規則' : '新增裝置例外'}
      </button>
      <Feedback ok={ok} error={error} />
    </form>
  );
}

// ---------------------------------------------------------------- 使用者

const ROLES: Role[] = ['ADMIN', 'OPERATOR', 'VIEWER'];

function UsersPanel() {
  const users = useAsync(() => api.listUsers(), []);
  const [form, setForm] = useState<CreateUserRequest>({ username: '', password: '', role: 'VIEWER', displayName: '' });

  const { busy, ok, error, submit } = useSubmit(
    () => api.createUser({ ...form, username: form.username.trim().toLowerCase() }),
    (u) => {
      users.reload();
      setForm({ username: '', password: '', role: 'VIEWER', displayName: '' });
      return `已建立 ${u.username}（${ROLE_LABEL[u.role]}）`;
    },
  );

  return (
    <div className="split">
      <section className="panel">
        <header className="panel-head">
          <h2>使用者</h2>
          <span className="sub">角色只有三種，再細的權限矩陣在內部系統裡通常變成沒人敢動的負擔</span>
        </header>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>帳號</th>
                <th>名稱</th>
                <th>角色</th>
                <th>建立</th>
              </tr>
            </thead>
            <tbody>
              {(users.data ?? []).map((u) => (
                <tr key={u.id} className={u.enabled ? undefined : 'disabled-row'}>
                  <td className="mono">{u.username}</td>
                  <td>{u.displayName}</td>
                  <td>
                    <span className={`role role-${u.role}`}>{ROLE_LABEL[u.role]}</span>
                  </td>
                  <td className="sub">{formatTime(u.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className="role-legend">
          <div><dt><span className="role role-ADMIN">管理員</span></dt><dd>改機型、機櫃、規則、使用者；看稽核</dd></div>
          <div><dt><span className="role role-OPERATOR">值班工程師</span></dt><dd>註冊裝置、調整監控樹；不能改會影響所有裝置的設定</dd></div>
          <div><dt><span className="role role-VIEWER">唯讀</span></dt><dd>只看</dd></div>
        </dl>
      </section>

      <section className="panel">
        <header className="panel-head">
          <h2>新增使用者</h2>
        </header>
        <form className="form" onSubmit={submit}>
          <div className="field-row">
            <label className="field">
              <span>帳號</span>
              <input className="input mono" autoComplete="off" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
            </label>
            <label className="field">
              <span>名稱</span>
              <input className="input" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
            </label>
          </div>
          <div className="field-row">
            <label className="field">
              <span>密碼（至少 8 字元）</span>
              <input className="input" type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
            </label>
            <label className="field">
              <span>角色</span>
              <select className="input" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })}>
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {ROLE_LABEL[r]}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <button className="btn btn-primary" type="submit" disabled={busy || !form.username.trim() || form.password.length < 8}>
            {busy ? '送出中…' : '建立使用者'}
          </button>
          <Feedback ok={ok} error={error} />
        </form>
      </section>
    </div>
  );
}

// ---------------------------------------------------------------- 稽核

const OUTCOME_LABEL: Record<AuditEntry['outcome'], string> = {
  OK: '成功',
  REJECTED: '被規則拒絕',
  DENIED: '權限不足',
  FAILED: '系統錯誤',
};

function AuditPanel() {
  const [actor, setActor] = useState('');
  const entries = useAsync(() => api.listAudit({ limit: 200, actor: actor.trim() || undefined }), [actor]);
  const [open, setOpen] = useState<number | null>(null);

  return (
    <section className="panel">
      <header className="panel-head">
        <h2>稽核紀錄</h2>
        <div className="panel-tools">
          <input className="input mono" placeholder="依帳號篩選" value={actor} onChange={(e) => setActor(e.target.value)} />
          <button className="btn" onClick={entries.reload}>
            重新整理
          </button>
          <span className="sub">{formatInt((entries.data ?? []).length)} 筆</span>
        </div>
      </header>
      <p className="hint">每一個會改東西的請求都在這裡，包括被擋下的。被擋下的比成功的更值得看：那是「誰試圖做什麼」。</p>
      {entries.loading && <p className="empty">載入中…</p>}
      {entries.error && <p className="error">{entries.error}</p>}
      <div className="table-scroll tall">
        <table className="data-table audit-table">
          <thead>
            <tr>
              <th>時間</th>
              <th>操作者</th>
              <th>動作</th>
              <th>結果</th>
              <th>明細</th>
            </tr>
          </thead>
          <tbody>
            {(entries.data ?? []).map((e) => {
              const reply = e.detail && typeof e.detail.reply === 'string' ? (e.detail.reply as string) : null;
              const replyMessage = reply ? messageOfReply(reply) : null;
              return (
                <tr key={e.id} className={`audit-${e.outcome}`} onClick={() => setOpen(open === e.id ? null : e.id)}>
                  <td className="sub" title={formatTime(e.at)}>
                    {formatRelative(e.at)}
                  </td>
                  <td className="mono">{e.actor}</td>
                  <td className="mono">{e.action}</td>
                  <td>
                    <span className={`outcome outcome-${e.outcome}`}>{OUTCOME_LABEL[e.outcome]}</span>
                  </td>
                  <td className="audit-detail">
                    {open === e.id ? (
                      <pre className="mono">{JSON.stringify(e.detail, null, 2)}</pre>
                    ) : (
                      <span className="sub">{replyMessage ?? (e.detail && 'body' in e.detail ? '點開看請求內容' : '—')}</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function messageOfReply(reply: string): string {
  try {
    const parsed = JSON.parse(reply) as { message?: string };
    return parsed.message ?? reply;
  } catch {
    return reply;
  }
}
