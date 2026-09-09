"""每隔 N 秒抓一次 API 與模擬器的 Prometheus 指標，印出壓測關鍵數字。用法：sample_load.py <秒數> <次數>"""
import re, sys, time, urllib.request, subprocess, json

INTERVAL = int(sys.argv[1]) if len(sys.argv) > 1 else 15
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 12


def scrape(url):
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            return r.read().decode()
    except Exception as e:
        return ''


def val(text, name, labels=None, agg='sum'):
    vals = []
    for line in text.splitlines():
        if not line.startswith(name):
            continue
        if labels and not all(l in line for l in labels):
            continue
        try:
            vals.append(float(line.rsplit(' ', 1)[1]))
        except ValueError:
            pass
    if not vals:
        return None
    return sum(vals) if agg == 'sum' else max(vals)


def docker_stats():
    try:
        out = subprocess.run(['docker', 'stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}} {{.MemUsage}}'],
                             capture_output=True, text=True, timeout=20).stdout
        return ' | '.join(l.replace('iot-', '') for l in out.splitlines() if l.startswith('iot-'))
    except Exception:
        return ''


prev = None
print(f"{'t':>5} {'pub/s':>8} {'ingest/s':>9} {'e2e p95':>8} {'e2e p99':>8} {'lag max':>8} {'batch p95':>9} {'size avg':>9} {'hik.pend':>8} {'heap MB':>8} {'alarm+':>7}")
for i in range(ROUNDS):
    api = scrape('http://localhost:8090/actuator/prometheus')
    sim = scrape('http://localhost:8081/actuator/prometheus')
    now = time.time()
    ingested = val(api, 'telemetry_ingested_total')
    published = val(sim, 'simulator_points_published_total')
    fired = val(api, 'alarm_fired_total')
    bsum = val(api, 'telemetry_ingest_batch_size_sum')
    bcount = val(api, 'telemetry_ingest_batch_size_count')
    row = {
        'e2e95': val(api, 'telemetry_ingest_latency_seconds', ['quantile="0.95"'], 'max'),
        'e2e99': val(api, 'telemetry_ingest_latency_seconds', ['quantile="0.99"'], 'max'),
        'lag': val(api, 'kafka_consumer_fetch_manager_records_lag_max', None, 'max'),
        'wb95': val(api, 'telemetry_write_batch_seconds', ['quantile="0.95"'], 'max'),
        'pend': val(api, 'hikaricp_connections_pending'),
        'heap': (val(api, 'jvm_memory_used_bytes', ['area="heap"']) or 0) / 1e6,
        'conn': val(sim, 'simulator_devices_connected'),
    }
    if prev:
        dt = now - prev['t']
        pub_rate = (published - prev['pub']) / dt if published is not None and prev['pub'] is not None else None
        ing_rate = (ingested - prev['ing']) / dt if ingested is not None and prev['ing'] is not None else None
        size_avg = ((bsum - prev['bsum']) / (bcount - prev['bcount'])) if bsum is not None and prev['bsum'] is not None and bcount != prev['bcount'] else None
        fired_d = (fired - prev['fired']) if fired is not None and prev['fired'] is not None else None
        f = lambda x, d=0: '—' if x is None else f'{x:.{d}f}'
        print(f"{int(now - start):>5} {f(pub_rate):>8} {f(ing_rate):>9} {f(row['e2e95'], 2):>8} {f(row['e2e99'], 2):>8} {f(row['lag']):>8} {f(row['wb95'], 3):>9} {f(size_avg):>9} {f(row['pend']):>8} {f(row['heap']):>8} {f(fired_d):>7}  conn={f(row['conn'])}")
    else:
        start = now
        print(f"  start: connected={row['conn']} heap={row['heap']:.0f}MB")
    prev = {'t': now, 'pub': published, 'ing': ingested, 'bsum': bsum, 'bcount': bcount, 'fired': fired}
    if i == ROUNDS // 2:
        print('  docker:', docker_stats())
    time.sleep(INTERVAL)
print('  docker:', docker_stats())
