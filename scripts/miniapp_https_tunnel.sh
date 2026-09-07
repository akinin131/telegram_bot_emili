#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$ROOT_DIR/.miniapp-tunnel"
PID_FILE="$STATE_DIR/cloudflared.pid"
LOG_FILE="$STATE_DIR/cloudflared.log"
URL_FILE="$STATE_DIR/cloudflared.url"

PORT="${MINIAPP_PORT:-8080}"
LOCAL_URL="${MINIAPP_LOCAL_URL:-http://localhost:${PORT}}"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-cloudflared}"

mkdir -p "$STATE_DIR"

require_cloudflared() {
  if ! command -v "$CLOUDFLARED_BIN" >/dev/null 2>&1; then
    echo "cloudflared not found. Install it first: brew install cloudflared" >&2
    exit 1
  fi
}

is_running() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [[ -n "$pid" ]] || return 1
  kill -0 "$pid" 2>/dev/null
}

print_status() {
  if is_running; then
    echo "Tunnel status: running"
    echo "PID: $(cat "$PID_FILE")"
    echo "Local URL: $LOCAL_URL"
    if [[ -f "$URL_FILE" ]]; then
      echo "Public URL: $(cat "$URL_FILE")"
    else
      echo "Public URL: not discovered yet"
    fi
    echo "Log: $LOG_FILE"
  else
    echo "Tunnel status: stopped"
  fi
}

start_tunnel() {
  require_cloudflared

  if is_running; then
    echo "Tunnel is already running."
    print_status
    return 0
  fi

  rm -f "$LOG_FILE" "$URL_FILE"

  local attempt
  for attempt in 1 2 3; do
    : > "$LOG_FILE"

    nohup "$CLOUDFLARED_BIN" tunnel \
      --url "$LOCAL_URL" \
      --protocol http2 \
      --no-autoupdate \
      >"$LOG_FILE" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"

    local url=""
    for _ in {1..60}; do
      if ! kill -0 "$pid" 2>/dev/null; then
        break
      fi

      url="$(grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' "$LOG_FILE" | head -n 1 || true)"
      if [[ -n "$url" ]]; then
        echo "$url/miniapp/" > "$URL_FILE"
        echo "Tunnel started."
        print_status
        return 0
      fi
      sleep 1
    done

    rm -f "$PID_FILE"
    if [[ "$attempt" -lt 3 ]]; then
      echo "Quick tunnel start failed, retrying ($attempt/3)..."
      sleep 2
    fi
  done

  echo "cloudflared exited early after retries. Check log: $LOG_FILE" >&2
  cat "$LOG_FILE" >&2 || true
  exit 1
}

stop_tunnel() {
  if ! is_running; then
    echo "Tunnel is already stopped."
    rm -f "$PID_FILE"
    return 0
  fi

  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true

  for _ in {1..20}; do
    if ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 0.5
  done

  rm -f "$PID_FILE"
  echo "Tunnel stopped."
}

case "${1:-start}" in
  start)
    start_tunnel
    ;;
  stop)
    stop_tunnel
    ;;
  status)
    print_status
    ;;
  restart)
    stop_tunnel
    start_tunnel
    ;;
  *)
    echo "Usage: $0 {start|stop|status|restart}" >&2
    exit 1
    ;;
esac
