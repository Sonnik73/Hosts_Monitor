#!/usr/bin/env bash
# ServerMon agent installer. Run as root on the target server:
#   sudo ./install.sh [config.json]
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_BIN="/usr/local/bin/servermon-agent.py"
CONF_DIR="/etc/servermon"
CONF_FILE="$CONF_DIR/config.json"
UNIT_FILE="/etc/systemd/system/servermon-agent.service"

[[ $EUID -eq 0 ]] || { echo "Run as root: sudo ./install.sh"; exit 1; }

install -m 755 "$SRC_DIR/agent.py" "$AGENT_BIN"
mkdir -p "$CONF_DIR"

if [[ ! -f "$CONF_FILE" ]]; then
  if [[ -n "${1:-}" ]]; then
    install -m 600 "$1" "$CONF_FILE"
  else
    echo "No config provided — installing example config. EDIT IT before starting:"
    echo "  nano $CONF_FILE   (set the token, services, mounts, peers)"
    install -m 600 "$SRC_DIR/config.example.json" "$CONF_FILE"
  fi
fi

cat > "$UNIT_FILE" <<'EOF'
[Unit]
Description=ServerMon metrics agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /usr/local/bin/servermon-agent.py
Restart=always
RestartSec=10
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/etc/servermon
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now servermon-agent.service

echo
echo "Installed. Check status:"
echo "  systemctl status servermon-agent"
echo "  curl -H 'X-Auth-Token: YOUR_TOKEN' http://127.0.0.1:8443/metrics.json"
