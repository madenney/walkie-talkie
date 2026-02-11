#!/usr/bin/env bash
#
# Walkie Talkie server manager
#
# Usage: ./scripts/server.sh {start|stop|restart|status} [OPTIONS]
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source .env if present
if [[ -f "$PROJECT_ROOT/.env" ]]; then
    set -a
    source "$PROJECT_ROOT/.env"
    set +a
fi
SERVER_DIR="$PROJECT_ROOT/server"
PID_FILE="$SERVER_DIR/.server.pid"
LOG_FILE="$SERVER_DIR/server.log"
HOST="0.0.0.0"
PORT="8765"

usage() {
    cat <<EOF
Walkie Talkie Server

Usage: $(basename "$0") <command> [options]

Commands:
    start       Start the server in the background
    stop        Stop a running server
    restart     Stop then start the server
    status      Check if the server is running

Options:
    -h, --help          Show this help message
    -f, --foreground    Run in the foreground (start only)
    -p, --port PORT     Server port (default: $PORT)
    --host HOST         Bind address (default: $HOST)

Environment:
    ANTHROPIC_API_KEY   Required. Claude API key.
    OPENAI_API_KEY      Optional. Enables TTS.

Examples:
    $(basename "$0") start                  # start in background on :8765
    $(basename "$0") start -f               # start in foreground (ctrl-c to stop)
    $(basename "$0") start -p 9000          # start on port 9000
    $(basename "$0") stop                   # stop the server
    $(basename "$0") restart                # restart the server
    $(basename "$0") status                 # check if running

Log file: $LOG_FILE
PID file: $PID_FILE
EOF
}

is_running() {
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        else
            # stale pid file
            rm -f "$PID_FILE"
        fi
    fi
    return 1
}

do_start() {
    local foreground=false

    while [[ $# -gt 0 ]]; do
        case "$1" in
            -f|--foreground) foreground=true; shift ;;
            -p|--port) PORT="$2"; shift 2 ;;
            --host) HOST="$2"; shift 2 ;;
            *) echo "Unknown option: $1"; usage; exit 1 ;;
        esac
    done

    if is_running; then
        echo "Server already running (PID $(cat "$PID_FILE"))"
        exit 1
    fi

    if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
        echo "Error: ANTHROPIC_API_KEY is not set"
        exit 1
    fi

    cd "$SERVER_DIR"

    # Use venv if it exists
    if [[ -f "$SERVER_DIR/.venv/bin/python3" ]]; then
        PYTHON="$SERVER_DIR/.venv/bin/python3"
    else
        PYTHON="python3"
    fi

    if $foreground; then
        echo "Starting server on $HOST:$PORT (foreground)..."
        exec "$PYTHON" -m uvicorn src.main:app --host "$HOST" --port "$PORT"
    else
        echo "Starting server on $HOST:$PORT..."
        nohup "$PYTHON" -m uvicorn src.main:app --host "$HOST" --port "$PORT" \
            >> "$LOG_FILE" 2>&1 &
        local pid=$!
        echo "$pid" > "$PID_FILE"
        sleep 1
        if kill -0 "$pid" 2>/dev/null; then
            echo "Server started (PID $pid)"
            echo "Logs: $LOG_FILE"
        else
            echo "Server failed to start. Check $LOG_FILE"
            rm -f "$PID_FILE"
            exit 1
        fi
    fi
}

do_stop() {
    if ! is_running; then
        echo "Server is not running"
        return 0
    fi

    local pid
    pid=$(cat "$PID_FILE")
    echo "Stopping server (PID $pid)..."
    kill "$pid"

    # Wait up to 5 seconds for graceful shutdown
    for i in {1..10}; do
        if ! kill -0 "$pid" 2>/dev/null; then
            rm -f "$PID_FILE"
            echo "Server stopped"
            return 0
        fi
        sleep 0.5
    done

    # Force kill
    echo "Force killing..."
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "Server stopped"
}

do_status() {
    if is_running; then
        local pid
        pid=$(cat "$PID_FILE")
        echo "Server is running (PID $pid, port $PORT)"

        # Try health check
        if command -v curl &>/dev/null; then
            local health
            health=$(curl -s "http://localhost:$PORT/health" 2>/dev/null) || true
            if [[ -n "$health" ]]; then
                echo "Health: $health"
            fi
        fi
    else
        echo "Server is not running"
        return 1
    fi
}

# --- Main ---

if [[ $# -lt 1 ]]; then
    usage
    exit 1
fi

COMMAND="$1"
shift

case "$COMMAND" in
    start)   do_start "$@" ;;
    stop)    do_stop ;;
    restart) do_stop; do_start "$@" ;;
    status)  do_status ;;
    -h|--help|help) usage ;;
    *) echo "Unknown command: $COMMAND"; usage; exit 1 ;;
esac
