#!/usr/bin/env python3
"""ServerMon agent — lightweight metrics endpoint for the hosts-monitor Android app.

Serves GET /metrics.json, /discovery.json, /health and POST /watch,
protected by a token header (X-Auth-Token). Python 3 stdlib only.

All servers can share ONE config: peers list may contain every server
(including this one — self entries are detected and skipped).

Usage:
    python3 agent.py                          # config /etc/servermon/config.json
    python3 agent.py --config ./config.json   # explicit config path
    python3 agent.py --selftest               # collect metrics once, print JSON, exit
"""

import argparse
import glob
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_CONFIG_PATH = "/etc/servermon/config.json"
SAMPLE_INTERVAL = 5  # seconds between background CPU/net samples
CONFIG_PATH = DEFAULT_CONFIG_PATH  # real path set in main()/selftest


# ---------------------------------------------------------------------------
# /proc helpers — every read is guarded so the agent never dies on a weird host
# ---------------------------------------------------------------------------

def _read_first_line(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.readline().strip()
    except OSError:
        return ""


def get_hostname():
    return socket.gethostname()


def get_uptime_seconds():
    line = _read_first_line("/proc/uptime")
    if line:
        try:
            return round(float(line.split()[0]))
        except ValueError:
            pass
    # Fallback for non-Linux (dev machines)
    return round(time.monotonic())


def get_loadavg():
    try:
        l1, l5, l15 = os.getloadavg()
        return {"1": round(l1, 2), "5": round(l5, 2), "15": round(l15, 2)}
    except (OSError, AttributeError):
        return {"1": 0.0, "5": 0.0, "15": 0.0}


def get_cpu_count():
    return os.cpu_count() or 1


def get_memory():
    """Parse /proc/meminfo into used/total in MB."""
    info = {}
    try:
        with open("/proc/meminfo", "r", encoding="utf-8") as f:
            for line in f:
                parts = line.split(":")
                if len(parts) == 2:
                    key = parts[0].strip()
                    val = parts[1].strip().split()[0]
                    try:
                        info[key] = int(val)  # kB
                    except ValueError:
                        pass
    except OSError:
        return None
    if "MemTotal" not in info:
        return None
    total = info["MemTotal"]
    available = info.get("MemAvailable", info.get("MemFree", 0))
    used = total - available
    swap_total = info.get("SwapTotal", 0)
    swap_free = info.get("SwapFree", 0)
    return {
        "total_mb": round(total / 1024),
        "used_mb": round(used / 1024),
        "used_pct": round(used * 100.0 / total, 1) if total else 0.0,
        "swap_total_mb": round(swap_total / 1024),
        "swap_used_mb": round((swap_total - swap_free) / 1024),
    }


def get_disk(mount):
    try:
        st = os.statvfs(mount)
    except (OSError, AttributeError, FileNotFoundError):
        return {"mount": mount, "error": "unavailable"}
    total = st.f_blocks * st.f_frsize
    free = st.f_bavail * st.f_frsize
    used = total - free
    return {
        "mount": mount,
        "total_gb": round(total / 1024**3, 1),
        "used_gb": round(used / 1024**3, 1),
        "used_pct": round(used * 100.0 / total, 1) if total else 0.0,
    }


def get_cpu_temperature():
    best = None
    for path in glob.glob("/sys/class/thermal/thermal_zone*/temp"):
        raw = _read_first_line(path)
        try:
            t = int(raw) / 1000.0
        except ValueError:
            continue
        if 0 < t < 150 and (best is None or t > best):
            best = t  # hottest zone
    return round(best, 1) if best is not None else None


def _proc_stat_cpu():
    line = _read_first_line("/proc/stat")
    if not line.startswith("cpu"):
        return None
    fields = [int(x) for x in line.split()[1:9]]
    idle = fields[3] + fields[4]
    total = sum(fields)
    return idle, total


def _net_counters():
    rx = tx = 0
    try:
        with open("/proc/net/dev", "r", encoding="utf-8") as f:
            for line in f.readlines()[2:]:
                parts = line.split()
                if len(parts) < 10 or parts[0].rstrip(":") == "lo":
                    continue
                rx += int(parts[1])
                tx += int(parts[9])
    except (OSError, ValueError, IndexError):
        pass
    return rx, tx


def get_established_connections():
    count = 0
    for path in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            with open(path, "r", encoding="utf-8") as f:
                count += sum(1 for line in f.readlines()[1:] if len(line.split()) > 3 and line.split()[3] == "01")
        except OSError:
            pass
    return count


def get_top_processes(limit=15):
    """Top processes by CPU via ps; returns list of dicts."""
    try:
        out = subprocess.run(
            ["ps", "-eo", "pcpu,pmem,comm", "--sort=-pcpu", "--no-headers"],
            capture_output=True, text=True, timeout=5,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    procs = []
    for line in out.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) == 3:
            try:
                procs.append({"cpu_pct": float(parts[0]), "mem_pct": float(parts[1]), "name": parts[2]})
            except ValueError:
                continue
        if len(procs) >= limit:
            break
    return procs


def get_failed_ssh_logins(since="-24h"):
    """Count failed SSH password attempts; cached — journalctl over 24h of logs
    is the most expensive call here and shouldn't run on every request."""
    global _ssh_cache
    now = time.time()
    with _ssh_cache_lock:
        if _ssh_cache and now - _ssh_cache[0] < SSH_CACHE_TTL:
            return _ssh_cache[1]
    try:
        out = subprocess.run(
            ["journalctl", "_COMM=sshd", "--since", since, "--no-pager", "-q"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        count = sum(1 for line in out.splitlines() if "Failed password" in line or "Invalid user" in line)
    except (OSError, subprocess.SubprocessError):
        return None  # journalctl missing or not permitted
    with _ssh_cache_lock:
        _ssh_cache = (now, count)
    return count


# ---------------------------------------------------------------------------
# Watch lists (services/processes the app asked to monitor) — watch.json
# next to config.json, updated via POST /watch from the app
# ---------------------------------------------------------------------------

def _watch_path():
    return os.path.join(os.path.dirname(CONFIG_PATH) or ".", "watch.json")


def load_watch():
    try:
        with open(_watch_path(), "r", encoding="utf-8") as f:
            w = json.load(f)
    except (OSError, ValueError):
        return {}
    return w if isinstance(w, dict) else {}


def save_watch(watch):
    path = _watch_path()
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(watch, f)
    os.replace(tmp, path)


def get_watch_lists(config):
    """Effective watch lists: watch.json (app-pushed) overrides config defaults."""
    merged = dict(config.get("watch") or {})
    merged.update({k: v for k, v in load_watch().items() if v})
    return merged


# ---------------------------------------------------------------------------
# Service / process discovery
# ---------------------------------------------------------------------------

def get_own_ips():
    ips = {"127.0.0.1", "::1", "localhost"}
    try:
        out = subprocess.run(["hostname", "-I"], capture_output=True, text=True, timeout=3).stdout.split()
        ips.update(out)
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return ips


def list_enabled_services(limit=100):
    """Unit names of enabled services — things that *should* be running."""
    try:
        out = subprocess.run(
            ["systemctl", "list-unit-files", "--type=service", "--state=enabled",
             "--no-legend", "--no-pager"],
            capture_output=True, text=True, timeout=10,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    names = []
    for line in out.splitlines():
        if line.strip():
            names.append(line.split()[0])
    return names[:limit]


def get_service_status(services):
    """systemctl is-active for a list; batched into few subprocess calls."""
    states = {}
    services = list(services)
    for i in range(0, len(services), 40):
        chunk = services[i:i + 40]
        try:
            r = subprocess.run(
                ["systemctl", "is-active"] + chunk,
                capture_output=True, text=True, timeout=8,
            )
            lines = r.stdout.split()
            for svc, st in zip(chunk, lines):
                states[svc] = st or "unknown"
            for svc in chunk[len(lines):]:
                states[svc] = "unknown"
        except (OSError, subprocess.SubprocessError):
            for svc in chunk:
                states[svc] = "unknown"
    return states


def get_discovery():
    """Everything the app needs to let the user pick what to watch."""
    enabled = list_enabled_services()
    statuses = get_service_status(enabled)
    services = [{"unit": name, "active": statuses.get(name) == "active"} for name in enabled]

    agg = {}
    try:
        out = subprocess.run(
            ["ps", "-eo", "pcpu=,pmem=,comm=", "--no-headers"],
            capture_output=True, text=True, timeout=8,
        ).stdout
        for line in out.splitlines():
            parts = line.strip().split(None, 2)
            if len(parts) != 3:
                continue
            try:
                cpu, mem = float(parts[0]), float(parts[1])
            except ValueError:
                continue
            comm = parts[2]
            entry = agg.setdefault(comm, {"name": comm, "count": 0, "cpu_pct": 0.0, "mem_pct": 0.0})
            entry["count"] += 1
            entry["cpu_pct"] += cpu
            entry["mem_pct"] += mem
    except (OSError, subprocess.SubprocessError):
        pass
    processes = sorted(agg.values(), key=lambda e: -e["cpu_pct"])[:100]
    for e in processes:
        e["cpu_pct"] = round(e["cpu_pct"], 1)
        e["mem_pct"] = round(e["mem_pct"], 1)

    return {
        "hostname": get_hostname(),
        "ips": sorted(ip for ip in get_own_ips() if not ip.startswith("127.") and ip != "localhost"),
        "services": services,
        "processes": processes,
    }


def get_watched_processes(names):
    """Map name -> running count for the watched process names (pgrep -x)."""
    result = {}
    for n in names:
        try:
            r = subprocess.run(["pgrep", "-cx", "--", n], capture_output=True, text=True, timeout=5)
            result[n] = int(r.stdout.strip() or 0)
        except (OSError, ValueError, subprocess.SubprocessError):
            result[n] = -1  # unknown (pgrep missing)
    return result


# ---------------------------------------------------------------------------
# Peer connectivity + relayed state
# ---------------------------------------------------------------------------

PEER_CACHE_TTL = 60  # seconds — dampens poll cascades (app polls agent, agent polls peers)
SSH_CACHE_TTL = 300  # seconds

_peer_cache_lock = threading.Lock()
_peer_cache = None  # (timestamp, peers list)
_ssh_cache_lock = threading.Lock()
_ssh_cache = None  # (timestamp, count)


def get_peers_cached(config):
    """Peer checks (TCP + HTTP fetch of peer metrics) cached for PEER_CACHE_TTL."""
    global _peer_cache
    now = time.time()
    with _peer_cache_lock:
        if _peer_cache and now - _peer_cache[0] < PEER_CACHE_TTL:
            return _peer_cache[1]

    peers_cfg = [p for p in config.get("peers", []) if isinstance(p, dict)]
    own_ips = get_own_ips()
    own_hostname = get_hostname()
    token = (config.get("token") or "").strip()
    with ThreadPoolExecutor(max_workers=8) as ex:
        peers = list(ex.map(lambda p: check_peer(p, own_ips, own_hostname, token), peers_cfg))
    # drop self-entries from the report — the app knows who is who
    peers = [p for p in peers if p.get("ok") is not None]

    with _peer_cache_lock:
        _peer_cache = (now, peers)
    return peers

def _fetch_peer_summary(host, port, token):
    """Fetch a peer's /metrics.json and extract a small summary (no recursion)."""
    url = "http://%s:%s/metrics.json" % (host, port)
    req = urllib.request.Request(url, headers={"X-Auth-Token": token or ""})
    try:
        with urllib.request.urlopen(req, timeout=3.5) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
    except (urllib.error.URLError, ValueError, OSError):
        return None
    mem = data.get("memory") or {}
    return {
        "hostname": data.get("hostname", ""),
        "uptime_sec": data.get("uptime_sec", 0),
        "cpu_pct": (data.get("cpu") or {}).get("usage_pct", 0.0),
        "mem_pct": mem.get("used_pct", 0.0),
    }


def check_peer(peer, own_ips, own_hostname, token):
    """TCP-connect check to a peer; if reachable, also relay its summary state."""
    host = peer.get("host", "")
    port = peer.get("port")
    name = peer.get("name", host)
    if not host or not port:
        return {"name": name, "ok": False, "latency_ms": None, "error": "bad peer config", "state": None}
    if host in own_ips or name == own_hostname:
        return {"name": name, "ok": None, "latency_ms": None, "error": "self", "state": None}

    start = time.monotonic()
    try:
        with socket.create_connection((host, int(port)), timeout=3.0):
            latency = (time.monotonic() - start) * 1000.0
    except OSError as exc:
        return {"name": name, "ok": False, "latency_ms": None,
                "error": str(exc) or exc.__class__.__name__, "state": None}

    result = {"name": name, "ok": True, "latency_ms": round(latency, 1), "error": None, "state": None}
    result["state"] = _fetch_peer_summary(host, port, token)
    return result


# ---------------------------------------------------------------------------
# Background sampler — keeps cpu%/net-rate meaningful between HTTP polls
# ---------------------------------------------------------------------------

class Sampler:
    def __init__(self):
        self.lock = threading.Lock()
        self.cpu_pct = 0.0
        self.net_rx_bps = 0  # bytes per second
        self.net_tx_bps = 0
        # Instantaneous per-process stats over the last 1 s window:
        self.top = []       # [{name, count, cpu_pct, mem_pct}] sorted by cpu
        self.d_state = 0    # processes waiting on uninterruptible disk IO
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._loop, daemon=True)

    def start(self):
        self._sample()  # fill initial values
        self._thread.start()

    def stop(self):
        self._stop.set()

    def _loop(self):
        while not self._stop.wait(SAMPLE_INTERVAL):
            try:
                self._sample()
            except Exception:
                pass  # never crash the sampler thread

    def _sample(self):
        try:
            clk_tck = os.sysconf("SC_CLK_TCK")
        except (ValueError, OSError, AttributeError):
            clk_tck = 100
        try:
            page = os.sysconf("SC_PAGE_SIZE")
        except (ValueError, OSError, AttributeError):
            page = 4096

        prev = _proc_stat_cpu() or (0, 1)
        prev_rx, prev_tx = _net_counters()
        prev_procs = _read_procs()
        time.sleep(1.0)
        cur = _proc_stat_cpu()
        rx, tx = _net_counters()
        cur_procs = _read_procs()

        total_mb = None
        mem = get_memory()
        if mem:
            total_mb = mem["total_mb"]

        # instantaneous per-process CPU over this 1 s window (100 % = one core)
        agg = {}
        d_state = 0
        for pid, proc in cur_procs.items():
            ticks, rss, state, comm = proc
            if state == "D":
                d_state += 1
            prev_ticks = prev_procs.get(pid, (0,))[0]
            delta = max(0, ticks - prev_ticks)
            cpu = delta / float(clk_tck) * 100.0
            entry = agg.setdefault(comm, {"name": comm, "count": 0, "cpu_pct": 0.0, "mem_pct": 0.0})
            entry["count"] += 1
            entry["cpu_pct"] += cpu
            if total_mb:
                entry["mem_pct"] += rss * page / (total_mb * 1024 * 1024) * 100.0

        top = sorted(agg.values(), key=lambda e: -e["cpu_pct"])[:30]
        for e in top:
            e["cpu_pct"] = round(e["cpu_pct"], 1)
            e["mem_pct"] = round(e["mem_pct"], 1)

        with self.lock:
            if cur:
                idle_d = cur[0] - prev[0]
                total_d = cur[1] - prev[1]
                if total_d > 0:
                    self.cpu_pct = round(100.0 * (1.0 - idle_d / total_d), 1)
                    self.cpu_pct = max(0.0, min(100.0, self.cpu_pct))
            self.net_rx_bps = max(0, rx - prev_rx)
            self.net_tx_bps = max(0, tx - prev_tx)
            self.top = top
            self.d_state = d_state


def _read_procs():
    """{pid: (total_ticks, rss_bytes, state, comm)} for all processes."""
    result = {}
    try:
        entries = os.listdir("/proc")
    except OSError:
        return result  # not Linux
    for entry in entries:
        if not entry.isdigit():
            continue
        try:
            with open("/proc/%s/stat" % entry, "r", encoding="utf-8", errors="replace") as f:
                line = f.readline()
            start = line.index("(")
            end = line.rindex(")")
            comm = line[start + 1:end]
            rest = line[end + 2:].split()
            state = rest[0]
            ticks = int(rest[11]) + int(rest[12])  # utime + stime
            rss = 0
            try:
                with open("/proc/%s/statm" % entry, "r", encoding="utf-8") as f:
                    rss = int(f.read().split()[1]) * 4096  # resident pages
            except (OSError, ValueError, IndexError):
                pass
            result[int(entry)] = (ticks, rss, state, comm)
        except (OSError, ValueError, IndexError):
            continue
    return result


# ---------------------------------------------------------------------------
# Metrics snapshot
# ---------------------------------------------------------------------------

def collect_metrics(config, sampler):
    memory = get_memory()
    watch = get_watch_lists(config)

    # Watched services if the app selected them; otherwise enabled services
    # (enabled-but-inactive is already an anomaly worth seeing).
    svc_names = watch.get("services") or []
    if not isinstance(svc_names, list):
        svc_names = []
    if not svc_names:
        svc_names = list_enabled_services(60)
    services = get_service_status([s for s in svc_names if isinstance(s, str)])

    proc_names = watch.get("processes") or []
    if not isinstance(proc_names, list):
        proc_names = []
    watched_processes = get_watched_processes([p for p in proc_names if isinstance(p, str)])

    peers = get_peers_cached(config)

    with sampler.lock:
        cpu_pct = sampler.cpu_pct
        rx_bps, tx_bps = sampler.net_rx_bps, sampler.net_tx_bps
        top = sampler.top
        d_state = sampler.d_state
    loadavg = get_loadavg()
    cores = get_cpu_count()
    return {
        "agent_version": 2,
        "timestamp": int(time.time()),
        "hostname": get_hostname(),
        "uptime_sec": get_uptime_seconds(),
        "cpu": {
            "cores": cores,
            "usage_pct": cpu_pct,
            "loadavg": loadavg,
            "load_per_core": round(loadavg["5"] / max(1, cores), 2),
            "temp_c": get_cpu_temperature(),
        },
        "memory": memory,
        "disks": [get_disk(m) for m in config.get("mounts", [])],
        "network": {
            "rx_bps": rx_bps,
            "tx_bps": tx_bps,
            "established": get_established_connections(),
        },
        "services": services,
        "watched_processes": watched_processes,
        "peers": peers,
        # instantaneous per-process CPU over the agent's sampling window —
        # ps's lifetime averages hide the real culprits (kswapd, D-state storms)
        "top_processes": top[:15],
        "io_wait_procs": d_state,
        "failed_ssh_logins_24h": get_failed_ssh_logins(),
    }


# ---------------------------------------------------------------------------
# HTTP server
# ---------------------------------------------------------------------------

class MetricsHandler(BaseHTTPRequestHandler):
    config = {}
    sampler = None

    def _authorized(self):
        token = (self.config.get("token") or "").strip()
        return token and self.headers.get("X-Auth-Token") == token

    def do_GET(self):  # noqa: N802 (stdlib API)
        path = self.path.split("?")[0]
        if path not in ("/metrics.json", "/discovery.json", "/health"):
            self._send(404, {"error": "not found"})
            return

        # /health is deliberately unauthenticated: handy for local liveness checks.
        if path == "/health":
            self._send(200, {"ok": True, "hostname": get_hostname()})
            return

        if not self._authorized():
            self._send(403, {"error": "invalid token"})
            return

        try:
            if path == "/discovery.json":
                self._send(200, get_discovery())
            else:
                self._send(200, collect_metrics(self.config, self.sampler))
        except Exception as exc:  # last-resort guard: always answer JSON
            self._send(500, {"error": "collection failed: %s" % exc})

    def do_POST(self):  # noqa: N802 (stdlib API)
        if self.path.split("?")[0] != "/watch":
            self._send(404, {"error": "not found"})
            return
        if not self._authorized():
            self._send(403, {"error": "invalid token"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            data = json.loads(self.rfile.read(length).decode("utf-8"))
            watch = {
                "services": [str(s) for s in (data.get("services") or [])][:100],
                "processes": [str(s) for s in (data.get("processes") or [])][:100],
                "mounts": [str(s) for s in (data.get("mounts") or [])][:20],
            }
            save_watch(watch)
            self._send(200, {"ok": True})
        except (ValueError, OSError) as exc:
            self._send(400, {"error": "bad request: %s" % exc})

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # quiet: only errors reach stderr
        if args and ("403" in str(args[1]) or "500" in str(args[1])):
            sys.stderr.write("[agent] %s - %s\n" % (self.address_string(), fmt % args))


# ---------------------------------------------------------------------------
# Entry points
# ---------------------------------------------------------------------------

def load_config(path):
    global CONFIG_PATH
    try:
        with open(path, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        sys.exit("[agent] config not found: %s" % path)
    except json.JSONDecodeError as exc:
        sys.exit("[agent] invalid config JSON in %s: %s" % (path, exc))
    if not cfg.get("token"):
        sys.exit("[agent] 'token' must be set in config (see config.example.json)")
    CONFIG_PATH = os.path.abspath(path)
    return cfg


def selftest(cfg_path):
    """Collect metrics once without starting the HTTP server; print JSON."""
    cfg = load_config(cfg_path)
    sampler = Sampler()
    sampler._sample()
    metrics = collect_metrics(cfg, sampler)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    problems = []
    if metrics["memory"] is None:
        problems.append("/proc/meminfo unreadable (not Linux?)")
    if metrics["failed_ssh_logins_24h"] is None:
        problems.append("journalctl unavailable — ssh login counts will be null")
    for name, state in metrics["services"].items():
        if state == "unknown":
            problems.append("service '%s': systemctl unavailable" % name)
    if problems:
        print("\n[agent] warnings:", file=sys.stderr)
        for p in problems:
            print("  - " + p, file=sys.stderr)
    else:
        print("\n[agent] selftest OK", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description="ServerMon metrics agent")
    parser.add_argument("--config", default=DEFAULT_CONFIG_PATH, help="path to config.json")
    parser.add_argument("--selftest", action="store_true", help="collect metrics once and exit")
    args = parser.parse_args()

    cfg = load_config(args.config)
    if args.selftest:
        selftest(args.config)
        return

    sampler = Sampler()
    sampler.start()
    MetricsHandler.config = cfg
    MetricsHandler.sampler = sampler

    host = cfg.get("listen_host", "0.0.0.0")
    port = int(cfg.get("listen_port", 8443))
    server = ThreadingHTTPServer((host, port), MetricsHandler)
    print("[agent] serving on %s:%d (metrics, discovery, watch)" % (host, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
