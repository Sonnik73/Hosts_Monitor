#!/usr/bin/env python3
"""ServerMon agent — lightweight metrics endpoint for the hosts-monitor Android app.

Serves GET /metrics.json (JSON) protected by a token header (X-Auth-Token).
Python 3 stdlib only, no dependencies. Runs as a systemd service (see install.sh).

Usage:
    python3 agent.py                          # config /etc/servermon/config.json
    python3 agent.py --config ./config.json   # explicit config path
    python3 agent.py --selftest               # collect metrics once, print JSON, exit
"""

import argparse
import glob
import json
import os
import re
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_CONFIG_PATH = "/etc/servermon/config.json"
SAMPLE_INTERVAL = 5  # seconds between background CPU/net samples


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
    # Fallback for non-Linux (dev machines): process start isn't boot, but a sane monotonic marker
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
    result = {
        "total_mb": round(total / 1024),
        "used_mb": round(used / 1024),
        "used_pct": round(used * 100.0 / total, 1) if total else 0.0,
        "swap_total_mb": round(swap_total / 1024),
        "swap_used_mb": round((swap_total - swap_free) / 1024),
    }
    return result


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
    fields = [int(x) for x in line.split()[1:9]]  # user nice system idle iowait irq softirq steal
    idle = fields[3] + fields[4]  # idle + iowait
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


def get_top_processes(limit=5):
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
    """Count failed SSH password attempts in the given window."""
    try:
        out = subprocess.run(
            ["journalctl", "_COMM=sshd", "--since", since, "--no-pager", "-q"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        return sum(1 for line in out.splitlines() if "Failed password" in line or "Invalid user" in line)
    except (OSError, subprocess.SubprocessError):
        return None  # journalctl missing or not permitted


# ---------------------------------------------------------------------------
# Service status and peer connectivity checks
# ---------------------------------------------------------------------------

def get_service_status(services):
    states = {}
    for svc in services:
        try:
            r = subprocess.run(
                ["systemctl", "is-active", svc],
                capture_output=True, text=True, timeout=5,
            )
            states[svc] = r.stdout.strip() or "unknown"
        except (OSError, subprocess.SubprocessError):
            states[svc] = "unknown"
    return states


def check_peer(peer):
    """TCP-connect check to a peer; returns reachability + latency in ms."""
    host = peer.get("host", "")
    port = peer.get("port")
    name = peer.get("name", host)
    if not host or not port:
        return {"name": name, "ok": False, "latency_ms": None, "error": "bad peer config"}
    start = time.monotonic()
    try:
        with socket.create_connection((host, int(port)), timeout=3.0):
            latency = (time.monotonic() - start) * 1000.0
            return {"name": name, "ok": True, "latency_ms": round(latency, 1), "error": None}
    except OSError as exc:
        return {"name": name, "ok": False, "latency_ms": None, "error": str(exc) or exc.__class__.__name__}


# ---------------------------------------------------------------------------
# Background sampler — keeps cpu%/net-rate meaningful between HTTP polls
# ---------------------------------------------------------------------------

class Sampler:
    def __init__(self):
        self.lock = threading.Lock()
        self.cpu_pct = 0.0
        self.net_rx_bps = 0  # bytes per second
        self.net_tx_bps = 0
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
        prev_idle, prev_total = _proc_stat_cpu() or (0, 1)
        prev_rx, prev_tx = _net_counters()
        time.sleep(1.0)
        cur = _proc_stat_cpu()
        rx, tx = _net_counters()
        with self.lock:
            if cur:
                idle_d = cur[0] - prev_idle
                total_d = cur[1] - prev_total
                if total_d > 0:
                    self.cpu_pct = round(100.0 * (1.0 - idle_d / total_d), 1)
                    self.cpu_pct = max(0.0, min(100.0, self.cpu_pct))
            self.net_rx_bps = max(0, rx - prev_rx)
            self.net_tx_bps = max(0, tx - prev_tx)


# ---------------------------------------------------------------------------
# Metrics snapshot
# ---------------------------------------------------------------------------

def collect_metrics(config, sampler):
    memory = get_memory()
    services = get_service_status(config.get("services", []))
    peers = [check_peer(p) for p in config.get("peers", [])]
    with sampler.lock:
        cpu_pct = sampler.cpu_pct
        rx_bps, tx_bps = sampler.net_rx_bps, sampler.net_tx_bps
    return {
        "agent_version": 1,
        "timestamp": int(time.time()),
        "hostname": get_hostname(),
        "uptime_sec": get_uptime_seconds(),
        "cpu": {
            "cores": get_cpu_count(),
            "usage_pct": cpu_pct,
            "loadavg": get_loadavg(),
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
        "peers": peers,
        "top_processes": get_top_processes(config.get("top_processes_limit", 5)),
        "failed_ssh_logins_24h": get_failed_ssh_logins(),
    }


# ---------------------------------------------------------------------------
# HTTP server
# ---------------------------------------------------------------------------

class MetricsHandler(BaseHTTPRequestHandler):
    config = {}
    sampler = None

    def do_GET(self):  # noqa: N802 (stdlib API)
        path = self.path.split("?")[0]
        if path not in ("/metrics.json", "/health"):
            self._send(404, {"error": "not found"})
            return

        # /health is deliberately unauthenticated: handy for local liveness checks.
        if path == "/health":
            self._send(200, {"ok": True, "hostname": get_hostname()})
            return

        token = (self.config.get("token") or "").strip()
        if token and self.headers.get("X-Auth-Token") != token:
            self._send(403, {"error": "invalid token"})
            return

        try:
            metrics = collect_metrics(self.config, self.sampler)
            self._send(200, metrics)
        except Exception as exc:  # last-resort guard: always answer JSON
            self._send(500, {"error": "metrics collection failed: %s" % exc})

    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):  # quiet: only errors reach stderr via log_error
        if args and ("403" in str(args[1]) or "500" in str(args[1])):
            sys.stderr.write("[agent] %s - %s\n" % (self.address_string(), fmt % args))


# ---------------------------------------------------------------------------
# Entry points
# ---------------------------------------------------------------------------

def load_config(path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        sys.exit("[agent] config not found: %s" % path)
    except json.JSONDecodeError as exc:
        sys.exit("[agent] invalid config JSON in %s: %s" % (path, exc))
    if not cfg.get("token"):
        sys.exit("[agent] 'token' must be set in config (see config.example.json)")
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
    print("[agent] serving /metrics.json on %s:%d" % (host, port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
