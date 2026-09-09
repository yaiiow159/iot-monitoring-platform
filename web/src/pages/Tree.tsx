import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import type { AlarmSeverity, CreateNodeRequest, NodeKind, TreeNode, TreePathNode } from '../api/types';
import { AlarmTable } from '../components/AlarmTable';
import { useAsync } from '../hooks/useAsync';
import { can, useSession } from '../auth/session';
import { useLive, useLiveNodes } from '../live/LiveContext';
import { SEVERITY_LABEL, formatInt } from '../utils/format';

/*
 * 監控樹：Equipment → Sensor（無限嵌套）→ Device。
 * 這一頁最重要的兩件事：
 * 一、子節點的順序是後端的保證（依 (sortOrder, id)），前端只照 API 回傳的陣列順序渲染，
 *     不排序、不用 Map／Set 承接；上移／下移也是改完 sortOrder 後重抓那一層，讓後端說了算。
 * 二、告警要「上浮」：葉節點響了，整條祖先鏈都要看得到，所以推播進來時重抓整棵子樹的 rollup。
 */

const KIND_LABEL: Record<NodeKind, string> = { EQUIPMENT: '設備', SENSOR: '感測層', DEVICE: '裝置' };
const KIND_GLYPH: Record<NodeKind, string> = { EQUIPMENT: 'E', SENSOR: 'S', DEVICE: 'D' };
const KINDS: NodeKind[] = ['EQUIPMENT', 'SENSOR', 'DEVICE'];
const SEVERITIES: AlarmSeverity[] = ['CRITICAL', 'WARNING', 'INFO'];

/** 與後端預設間隔一致：排到最前／最後時就往外再留一格。 */
const SORT_GAP = 1000;

/** 上浮動畫每往上一層晚多少毫秒亮起，讓眼睛跟得上「從哪裡冒上來」。 */
const BUBBLE_STEP_MS = 140;
const BUBBLE_TOTAL_MS = 2400;

// ---------------------------------------------------------------- 純函式

function findNode(nodes: TreeNode[], id: number): TreeNode | null {
  for (const n of nodes) {
    if (n.id === id) return n;
    const hit = findNode(n.children, id);
    if (hit) return hit;
  }
  return null;
}

/** 回傳父節點；根層節點回 null，找不到回 undefined。 */
function findParent(nodes: TreeNode[], id: number, parent: TreeNode | null = null): TreeNode | null | undefined {
  for (const n of nodes) {
    if (n.id === id) return parent;
    const hit = findParent(n.children, id, n);
    if (hit !== undefined) return hit;
  }
  return undefined;
}

/** 以 id 換掉子樹，其餘位置原封不動——順序是後端的保證，這裡只做替換不做搬動。 */
function replaceNode(nodes: TreeNode[], updated: TreeNode): TreeNode[] {
  return nodes.map((n) => (n.id === updated.id ? updated : { ...n, children: replaceNode(n.children, updated) }));
}

function walk(nodes: TreeNode[], visit: (node: TreeNode) => void): void {
  for (const n of nodes) {
    visit(n);
    walk(n.children, visit);
  }
}

/** 預設只展開有告警的分支：一萬台裝置的樹打開不該是一整面牆。 */
function defaultExpanded(roots: TreeNode[]): Set<number> {
  const open = new Set<number>();
  walk(roots, (n) => {
    if (n.kind !== 'DEVICE' && n.rollup.firing > 0) open.add(n.id);
  });
  // 整棵樹都安靜時至少把根打開，否則頁面只剩三行字。
  if (open.size === 0) for (const r of roots) open.add(r.id);
  return open;
}

function withAll(set: Set<number>, ids: number[]): Set<number> {
  const next = new Set(set);
  for (const id of ids) next.add(id);
  return next;
}

function withoutAll(set: Set<number>, ids: number[]): Set<number> {
  const next = new Set(set);
  for (const id of ids) next.delete(id);
  return next;
}

// ---------------------------------------------------------------- 頁面

export function Tree() {
  const [roots, setRoots] = useState<TreeNode[] | null>(null);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  // 以下三個 Set／Record 只記「哪些 id 展開／待重抓／正在上浮」的 UI 狀態，不承接子節點本身。
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [stale, setStale] = useState<Set<number>>(new Set());
  const [bubbling, setBubbling] = useState<Record<number, number>>({});
  const [moving, setMoving] = useState(false);
  const [moveError, setMoveError] = useState<string | null>(null);

  const tree = useAsync(() => api.getTree(), []);
  useEffect(() => {
    if (!tree.data) return;
    setRoots(tree.data);
    setExpanded(defaultExpanded(tree.data));
  }, [tree.data]);

  // 按節點訂閱：只送根節點 id，後端展開成子樹下的裝置，只推狀態與告警。
  // 收合的分支也收得到告警（rollup 才會往上亮），而瀏覽器不必承受一萬台裝置的遙測。
  const rootIds = useMemo(() => (roots ?? []).map((r) => r.id), [roots]);
  useLiveNodes(rootIds);

  /**
   * 告警上浮的核心：不是只改葉節點，而是把整條祖先鏈標成「需重抓」，
   * 然後重抓鏈的根（Equipment）整棵子樹——rollup 由後端從子樹重算，前端不自己加減。
   */
  const bubble = useCallback((chain: number[]) => {
    setStale((prev) => withAll(prev, chain));
    api
      .getSubtree(chain[0])
      .then((subtree) => {
        setRoots((prev) => (prev ? replaceNode(prev, subtree) : prev));
        setExpanded((prev) => withAll(prev, chain));
        setStale((prev) => withoutAll(prev, chain));
        // 由葉往根依序點亮：鏈的最後一個是裝置節點，延遲 0；愈往上愈晚。
        const delays: Record<number, number> = {};
        chain.forEach((id, i) => {
          delays[id] = (chain.length - 1 - i) * BUBBLE_STEP_MS;
        });
        setBubbling((prev) => ({ ...prev, ...delays }));
        window.setTimeout(() => {
          setBubbling((prev) => {
            const next = { ...prev };
            for (const id of chain) delete next[id];
            return next;
          });
        }, BUBBLE_TOTAL_MS);
      })
      .catch(() => setStale((prev) => withoutAll(prev, chain)));
  }, []);

  const { alarms: liveAlarms } = useLive();
  const seenAlarmIds = useRef<Set<number> | null>(null);
  useEffect(() => {
    // 第一次看到的告警都已經在 REST 快照裡了，只對「之後才推進來」的做上浮。
    if (seenAlarmIds.current === null) {
      seenAlarmIds.current = new Set(liveAlarms.map((a) => a.alarmId));
      return;
    }
    const seen = seenAlarmIds.current;
    for (const a of liveAlarms) {
      if (seen.has(a.alarmId)) continue;
      seen.add(a.alarmId);
      if (a.ancestorIds.length > 0) bubble(a.ancestorIds);
    }
  }, [liveAlarms, bubble]);

  const toggle = useCallback((id: number) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const expandAll = () => {
    if (!roots) return;
    const all: number[] = [];
    walk(roots, (n) => n.kind !== 'DEVICE' && all.push(n.id));
    setExpanded(new Set(all));
  };

  const selected = roots && selectedId !== null ? findNode(roots, selectedId) : null;
  const parent = roots && selectedId !== null ? findParent(roots, selectedId) : undefined;
  // 兄弟陣列直接拿 API 回傳的 children（或根清單）：順序是後端的保證。
  const siblings = parent === undefined ? [] : parent === null ? (roots ?? []) : parent.children;
  const siblingIndex = selected ? siblings.findIndex((n) => n.id === selected.id) : -1;

  /**
   * 上移／下移：以 gapped sortOrder 算新值。往上＝上一個兄弟的 sortOrder 減掉它與再上一個的一半間隔，
   * 已經是第二個就直接減 SORT_GAP；往下對稱。改完不在前端搬位置，重抓那一層讓順序由後端決定。
   */
  const move = async (dir: -1 | 1) => {
    if (!selected || !roots || siblingIndex < 0) return;
    const neighbour = siblings[siblingIndex + dir];
    if (!neighbour) return;
    const beyond = siblings[siblingIndex + dir * 2];
    let sortOrder: number;
    if (!beyond) {
      sortOrder = neighbour.sortOrder + dir * SORT_GAP;
    } else {
      const gap = Math.abs(beyond.sortOrder - neighbour.sortOrder);
      if (gap < 2) {
        setMoveError('這一層的排序間隔已用盡，需要後端重新編號後才能再插入。');
        return;
      }
      sortOrder = neighbour.sortOrder + dir * Math.floor(gap / 2);
    }
    setMoving(true);
    setMoveError(null);
    try {
      await api.reorderNode(selected.id, sortOrder);
      if (parent) {
        const subtree = await api.getSubtree(parent.id);
        setRoots((prev) => (prev ? replaceNode(prev, subtree) : prev));
      } else {
        setRoots(await api.getTree());
      }
    } catch (err) {
      setMoveError(err instanceof Error ? err.message : String(err));
    } finally {
      setMoving(false);
    }
  };

  /** 新增節點後不在前端插入：重抓那一層讓順序由後端決定，跟上移／下移同一套做法 */
  const afterCreate = useCallback(async (created: TreeNode, parentId: number | null) => {
    if (parentId === null) {
      setRoots(await api.getTree());
    } else {
      const subtree = await api.getSubtree(parentId);
      setRoots((prev) => (prev ? replaceNode(prev, subtree) : prev));
      setExpanded((prev) => withAll(prev, [parentId]));
    }
    setSelectedId(created.id);
  }, []);

  const session = useSession();
  const canEdit = can(session?.user, 'operate');
  const [showRootForm, setShowRootForm] = useState(false);

  const summary = useMemo(() => {
    const counts: Record<NodeKind, number> = { EQUIPMENT: 0, SENSOR: 0, DEVICE: 0 };
    let firing = 0;
    if (roots) {
      walk(roots, (n) => counts[n.kind]++);
      for (const r of roots) firing += r.rollup.firing;
    }
    return { counts, firing };
  }, [roots]);

  return (
    <div className="page">
      <div className="tree-layout">
        <section className="panel">
          <header className="panel-head">
            <h2>
              監控樹
              {roots && (
                <span className="sub num">
                  {formatInt(summary.counts.EQUIPMENT)} 座設備・{formatInt(summary.counts.SENSOR)} 個感測層・
                  {formatInt(summary.counts.DEVICE)} 台裝置
                </span>
              )}
            </h2>
            <div className="panel-tools">
              <span className={`badge${summary.firing > 0 ? ' badge-alarm' : ''}`}>
                未解除 {formatInt(summary.firing)}
              </span>
              <button className="btn" disabled={!roots} onClick={() => roots && setExpanded(defaultExpanded(roots))}>
                只展開告警
              </button>
              <button className="btn" disabled={!roots} onClick={expandAll}>
                全部展開
              </button>
              <button className="btn" disabled={!roots} onClick={() => setExpanded(new Set())}>
                全部收合
              </button>
              {canEdit && (
                <button className="btn" onClick={() => setShowRootForm((v) => !v)}>
                  {showRootForm ? '收起' : '＋ 新增設備'}
                </button>
              )}
            </div>
          </header>

          {showRootForm && canEdit && (
            <NodeForm
              parent={null}
              onCreated={(n) => {
                setShowRootForm(false);
                void afterCreate(n, null);
              }}
            />
          )}

          <div className="legend">
            {KINDS.map((k) => (
              <span key={k} className="legend-item">
                <span className={`kind kind-${k}`}>{KIND_GLYPH[k]}</span>
                {KIND_LABEL[k]}
              </span>
            ))}
            {SEVERITIES.map((s) => (
              <span key={s} className="legend-item">
                <span className={`rollup-dot rollup-${s}`} />
                {SEVERITY_LABEL[s]}
              </span>
            ))}
            <span className="legend-item">
              <span className="rollup-dot rollup-NONE" />
              子樹無告警
            </span>
          </div>

          {tree.error && <p className="error">監控樹載入失敗：{tree.error}</p>}
          {tree.loading || !roots ? (
            <p className="empty">載入中…</p>
          ) : roots.length === 0 ? (
            <p className="empty">還沒有任何設備</p>
          ) : (
            <div className="tree-scroll">
              <TreeRows
                nodes={roots}
                depth={0}
                parentSeverity={null}
                expanded={expanded}
                selectedId={selectedId}
                stale={stale}
                bubbling={bubbling}
                onToggle={toggle}
                onSelect={setSelectedId}
              />
            </div>
          )}
        </section>

        <NodeDetail
          node={selected}
          roots={roots}
          canMoveUp={siblingIndex > 0}
          canMoveDown={siblingIndex >= 0 && siblingIndex < siblings.length - 1}
          moving={moving}
          moveError={moveError}
          onMove={move}
          onSelect={setSelectedId}
          canEdit={canEdit}
          onCreated={afterCreate}
        />
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- 樹的列

interface RowsProps {
  nodes: TreeNode[];
  depth: number;
  parentSeverity: AlarmSeverity | null;
  expanded: Set<number>;
  selectedId: number | null;
  stale: Set<number>;
  bubbling: Record<number, number>;
  onToggle(id: number): void;
  onSelect(id: number): void;
}

function TreeRows(props: RowsProps) {
  const { nodes, depth, parentSeverity, expanded, selectedId, stale, bubbling, onToggle, onSelect } = props;
  return (
    // 這一層的導線用父節點的 rollup 上色：告警從哪條線冒上來，一眼就能循著顏色往上看。
    <ul
      className={`tree-children${depth === 0 ? ' tree-root' : ''} guide-${parentSeverity ?? 'NONE'}`}
      role={depth === 0 ? 'tree' : 'group'}
    >
      {/* 順序是後端的保證：直接照 children 陣列渲染，這裡不排序也不轉成 Map／Set。 */}
      {nodes.map((node) => {
        const leaf = node.kind === 'DEVICE';
        const open = !leaf && expanded.has(node.id);
        const sev = node.rollup.severity ?? 'NONE';
        const delay = bubbling[node.id];
        const rowClass = [
          'tree-row',
          `row-sev-${sev}`,
          node.id === selectedId ? 'is-selected' : '',
          stale.has(node.id) ? 'is-stale' : '',
          delay !== undefined ? 'is-bubbling' : '',
        ]
          .filter(Boolean)
          .join(' ');
        const style = delay !== undefined ? ({ animationDelay: `${delay}ms` } as CSSProperties) : undefined;

        return (
          <li key={node.id} className="tree-node" role="treeitem" aria-expanded={leaf ? undefined : open}>
            <div
              className={rowClass}
              style={style}
              tabIndex={0}
              onClick={() => onSelect(node.id)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  onSelect(node.id);
                } else if (!leaf && (e.key === 'ArrowRight' || e.key === 'ArrowLeft')) {
                  if ((e.key === 'ArrowRight') !== open) onToggle(node.id);
                }
              }}
            >
              {leaf ? (
                // Device 是葉節點，契約規定下面不能再掛東西，所以連箭頭都不畫。
                <span className="tree-spacer" aria-hidden="true" />
              ) : (
                <button
                  type="button"
                  className={`tree-toggle${open ? ' open' : ''}`}
                  aria-label={open ? '收合' : '展開'}
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggle(node.id);
                  }}
                />
              )}
              <span className={`kind kind-${node.kind}`} title={KIND_LABEL[node.kind]}>
                {KIND_GLYPH[node.kind]}
              </span>
              <span className="tree-name">{node.name}</span>
              {leaf && node.deviceId && <span className="tree-device mono">{node.deviceId}</span>}
              {!leaf && !open && node.children.length > 0 && (
                <span className="tree-count sub num">{formatInt(node.children.length)}</span>
              )}
              <span className={`rollup rollup-${sev}`} title={rollupTitle(node)}>
                <span className="rollup-dot" style={style} />
                {node.rollup.firing > 0 && <span className="rollup-count">{formatInt(node.rollup.firing)}</span>}
              </span>
            </div>
            {open && node.children.length > 0 && (
              <TreeRows {...props} nodes={node.children} depth={depth + 1} parentSeverity={node.rollup.severity} />
            )}
            {open && node.children.length === 0 && <p className="tree-empty">尚無子節點</p>}
          </li>
        );
      })}
    </ul>
  );
}

function rollupTitle(node: TreeNode): string {
  if (!node.rollup.severity) return '子樹內沒有未解除告警';
  return `子樹內 ${node.rollup.firing} 則未解除，最高 ${SEVERITY_LABEL[node.rollup.severity]}`;
}

// ---------------------------------------------------------------- 右側詳情

interface DetailProps {
  node: TreeNode | null;
  roots: TreeNode[] | null;
  canMoveUp: boolean;
  canMoveDown: boolean;
  moving: boolean;
  moveError: string | null;
  onMove(dir: -1 | 1): void;
  onSelect(id: number): void;
  canEdit: boolean;
  onCreated(created: TreeNode, parentId: number | null): Promise<void>;
}

function NodeDetail({ node, roots, canMoveUp, canMoveDown, moving, moveError, onMove, onSelect, canEdit, onCreated }: DetailProps) {
  const nodeId = node?.id ?? null;
  const ancestors = useAsync(
    () => (nodeId === null ? Promise.resolve([] as TreePathNode[]) : api.getAncestors(nodeId)),
    [nodeId],
  );

  // 麵包屑的名字來自 /ancestors，但色點看本地樹：推播重抓後的 rollup 才是最新的。
  const crumbs = useMemo(
    () =>
      (ancestors.data ?? []).map((a) => {
        const local = roots ? findNode(roots, a.id) : null;
        return { ...a, rollup: local?.rollup ?? a.rollup };
      }),
    [ancestors.data, roots],
  );

  const deviceId = node?.deviceId ?? null;
  const firingCount = node?.rollup.firing ?? 0;
  const deviceAlarms = useAsync(
    () => (deviceId ? api.listAlarms({ deviceId, state: 'FIRING' }) : Promise.resolve([])),
    // rollup 變了就重抓：推播只帶摘要，明細還是要問 REST。
    [deviceId, firingCount],
  );

  const subtreeDevices = useMemo(() => {
    let n = 0;
    if (node) walk(node.children, (c) => c.kind === 'DEVICE' && n++);
    return n;
  }, [node]);

  if (!node) {
    return (
      <section className="panel tree-detail">
        <header className="panel-head">
          <h2>節點詳情</h2>
        </header>
        <p className="empty">點選左側任一節點，查看它的路徑、告警彙總與順序調整</p>
      </section>
    );
  }

  const sev = node.rollup.severity;

  return (
    <section className="panel tree-detail">
      <header className="panel-head">
        <h2>節點詳情</h2>
        <span className="sub mono">#{node.id}</span>
      </header>

      <nav className="crumbs" aria-label="路徑">
        {crumbs.map((c, i) => (
          <span key={c.id} className="crumb-wrap">
            {i > 0 && <span className="crumb-sep" aria-hidden="true" />}
            <button
              type="button"
              className={`crumb${c.id === node.id ? ' is-current' : ''}`}
              onClick={() => onSelect(c.id)}
            >
              <span className={`rollup-dot rollup-${c.rollup.severity ?? 'NONE'}`} />
              {c.name}
            </button>
          </span>
        ))}
      </nav>

      <div className="tree-title">
        <span className={`kind kind-${node.kind} kind-lg`}>{KIND_GLYPH[node.kind]}</span>
        <h1>{node.name}</h1>
        <span className="sub">{KIND_LABEL[node.kind]}</span>
      </div>

      <dl className="tree-facts">
        <div className="fact">
          <dt className="caption">未解除告警</dt>
          <dd className={`tree-firing sev-${sev ?? 'NONE'}`}>{formatInt(node.rollup.firing)}</dd>
        </div>
        <div className="fact">
          <dt className="caption">最高嚴重度</dt>
          <dd>{sev ? <span className={`sev sev-${sev}`}>{SEVERITY_LABEL[sev]}</span> : <span className="sub">無</span>}</dd>
        </div>
        <div className="fact">
          <dt className="caption">排序鍵</dt>
          <dd className="fact-value">{node.sortOrder}</dd>
        </div>
        {node.kind !== 'DEVICE' && (
          <>
            <div className="fact">
              <dt className="caption">直接子節點</dt>
              <dd className="fact-value">{formatInt(node.children.length)}</dd>
            </div>
            <div className="fact">
              <dt className="caption">子樹裝置</dt>
              <dd className="fact-value">{formatInt(subtreeDevices)}</dd>
            </div>
          </>
        )}
      </dl>

      <div className="tree-actions">
        <button className="btn" disabled={!canMoveUp || moving} onClick={() => onMove(-1)}>
          ↑ 上移
        </button>
        <button className="btn" disabled={!canMoveDown || moving} onClick={() => onMove(1)}>
          ↓ 下移
        </button>
        <span className="hint">同層順序由後端依 (sortOrder, id) 決定，這裡只送新的 sortOrder。</span>
      </div>
      {moveError && <p className="error">{moveError}</p>}

      {node.kind === 'DEVICE' && node.deviceId && (
        <div className="tree-section">
          <div className="tree-section-head">
            <span className="caption">裝置</span>
            <span className="mono">{node.deviceId}</span>
            <Link className="btn btn-primary" to={`/devices/${node.deviceId}`}>
              前往裝置詳情 →
            </Link>
          </div>
          {deviceAlarms.loading ? (
            <p className="empty">載入中…</p>
          ) : (
            <AlarmTable alarms={deviceAlarms.data ?? []} showDevice={false} emptyText="這台裝置目前沒有未解除告警" />
          )}
        </div>
      )}

      {node.kind !== 'DEVICE' && (
        <div className="tree-section">
          <div className="tree-section-head">
            <span className="caption">直接子節點</span>
            <span className="sub">依後端回傳順序</span>
          </div>
          {canEdit && <NodeForm parent={node} onCreated={(n) => onCreated(n, node.id)} />}
          {node.children.length === 0 ? (
            <p className="empty">尚無子節點</p>
          ) : (
            <div className="table-scroll">
              <table className="data-table compact">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>類型</th>
                    <th>名稱</th>
                    <th className="num">排序鍵</th>
                    <th>彙總</th>
                  </tr>
                </thead>
                <tbody>
                  {/* 順序是後端的保證：照 children 陣列列出，不排序。 */}
                  {node.children.map((c, i) => (
                    <tr key={c.id} className="tree-child-row" onClick={() => onSelect(c.id)}>
                      <td className="sub num">{i + 1}</td>
                      <td>
                        <span className={`kind kind-${c.kind}`}>{KIND_GLYPH[c.kind]}</span>
                      </td>
                      <td>{c.name}</td>
                      <td className="num mono">{c.sortOrder}</td>
                      <td>
                        <span className={`rollup rollup-${c.rollup.severity ?? 'NONE'}`}>
                          <span className="rollup-dot" />
                          {c.rollup.firing > 0 ? (
                            <span className="rollup-count">{formatInt(c.rollup.firing)}</span>
                          ) : (
                            <span className="sub">—</span>
                          )}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------- 新增節點

/**
 * 新增節點的表單。能選的類型由父節點決定，跟後端的嵌套規則同一份：
 * 根層只能是 Equipment；Sensor 底下可以是 Sensor 或 Device；Device 不能有子節點。
 * 選錯的話後端仍會拒絕，訊息原文顯示。
 */
function NodeForm({ parent, onCreated }: { parent: TreeNode | null; onCreated(created: TreeNode): void }) {
  const allowed: NodeKind[] = parent === null ? ['EQUIPMENT'] : parent.kind === 'EQUIPMENT' ? ['SENSOR'] : ['SENSOR', 'DEVICE'];
  const [kind, setKind] = useState<NodeKind>(allowed[0]);
  const [name, setName] = useState('');
  const [deviceId, setDeviceId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const effectiveKind = allowed.includes(kind) ? kind : allowed[0];
  const isDevice = effectiveKind === 'DEVICE';
  const canSubmit = !busy && (isDevice ? deviceId.trim() !== '' : name.trim() !== '');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setBusy(true);
    setError(null);
    const request: CreateNodeRequest = {
      kind: effectiveKind,
      // Device 節點的名字就用裝置代號，讓樹上的名字跟裝置頁對得起來
      name: isDevice ? name.trim() || deviceId.trim().toUpperCase() : name.trim(),
      parentId: parent?.id ?? null,
      deviceId: isDevice ? deviceId.trim().toUpperCase() : undefined,
    };
    try {
      const created = await api.createNode(request);
      setName('');
      setDeviceId('');
      onCreated(created);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form node-form" onSubmit={submit}>
      <div className="field-row">
        <label className="field">
          <span>類型</span>
          <select className="input" value={effectiveKind} onChange={(e) => setKind(e.target.value as NodeKind)} disabled={allowed.length === 1}>
            {allowed.map((k) => (
              <option key={k} value={k}>
                {KIND_LABEL[k]}
              </option>
            ))}
          </select>
        </label>
        {isDevice ? (
          <label className="field">
            <span>裝置代號</span>
            <input className="input mono" value={deviceId} onChange={(e) => setDeviceId(e.target.value)} placeholder="DEV-000001" />
          </label>
        ) : null}
        <label className="field">
          <span>名稱{isDevice ? '（可空）' : ''}</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder={parent === null ? '例如：3F 配電盤' : '例如：進風側'} />
        </label>
        <button className="btn btn-primary node-form-submit" type="submit" disabled={!canSubmit}>
          {busy ? '送出中…' : parent === null ? '新增設備' : `在「${parent.name}」下新增`}
        </button>
      </div>
      <p className="hint">
        {parent === null
          ? 'Equipment 只能在根層。'
          : parent.kind === 'EQUIPMENT'
            ? 'Equipment 底下只能掛 Sensor；Device 要再往下一層。'
            : 'Sensor 可以再掛 Sensor（無限嵌套）或 Device；Device 是最後一層。'}
        排到同層最後；順序之後可用上移／下移調整。
      </p>
      {error && (
        <p className="error" role="alert">
          <span className="caption">後端拒絕</span>
          {error}
        </p>
      )}
    </form>
  );
}
