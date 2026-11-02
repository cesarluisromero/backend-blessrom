#!/usr/bin/env bash
set -euo pipefail

ROOT=/opt/blessrom
STATE_DIR="$ROOT/monitoring"
STATE_FILE="$STATE_DIR/backend.state"
ENV_FILE="$ROOT/monitoring.env"
HEALTH_URL=${HEALTH_URL:-http://127.0.0.1:18082/q/health/ready}
PUBLIC_URL=${PUBLIC_URL:-https://api.blessrom.com/api/v1/system}

if test -r "$ENV_FILE"; then
	# shellcheck disable=SC1090
	set -a; source "$ENV_FILE"; set +a
fi

mkdir -p "$STATE_DIR"
previous=$(cat "$STATE_FILE" 2>/dev/null || printf 'UNKNOWN')
current=UP
details=""

if ! curl --fail --silent --show-error --max-time 10 "$HEALTH_URL" >/dev/null 2>&1; then
	current=DOWN
	details="health endpoint unavailable"
elif ! curl --fail --silent --show-error --max-time 15 "$PUBLIC_URL" >/dev/null 2>&1; then
	current=DOWN
	details="public API unavailable"
fi

printf '%s\n' "$current" >"$STATE_FILE"

notify() {
	local message=$1
	logger -t blessrom-monitor -- "$message"
	if test -n "${ALERT_WEBHOOK_URL:-}"; then
		curl --fail --silent --show-error --max-time 15 \
			-H 'Content-Type: text/plain; charset=utf-8' \
			--data-binary "$message" "$ALERT_WEBHOOK_URL" >/dev/null || true
	fi
}

if [[ "$current" == DOWN ]]; then
	notify "Blessrom API DOWN en $(hostname): $details"
	exit 1
fi

if [[ "$previous" == DOWN ]]; then
	notify "Blessrom API RECUPERADA en $(hostname)"
fi

