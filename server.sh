#!/usr/bin/env bash
# Spring Chat control script.
#
#   ./server.sh          start the server and open the command prompt
#   ./server.sh 9090     start on a different port
#   ./server.sh help     show this help
#
# At the > prompt, type a command:
#   help, ban <name>, unban <name>, stop,
#   admin add <name>, admin remove <name>, admin list
#   exit                 stop the server and close the prompt

set -euo pipefail

cd "$(dirname "$0")"
STATE_FILE=".server.env"   # PORT and admin token while the server runs
LOG_FILE="server.log"      # server output

SERVER_PID=""
PORT=""
TOKEN=""

usage() {
  cat <<'USAGE'
Spring Chat control script.

  ./server.sh          start the server and open the command prompt
  ./server.sh 9090     start on a different port
  ./server.sh help     show this help

At the > prompt, type a command:
  help, ?               show the command list
  ban <name>            ban a user and end their sessions
  unban <name>          remove a ban
  stop                  stop the server
  admin add <name>      give a user admin rights
  admin remove <name>   remove admin rights
  admin list            show all admins
  exit                  stop the server and close the prompt
USAGE
}

check_java() {
  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: No installed Java 17 found (java is not on your PATH)." >&2
    echo "       Install JDK 17 or newer, then run this script again." >&2
    exit 1
  fi

  local line major
  line="$(java -version 2>&1 | head -n1)"
  major="$(echo "$line" | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
  if [ -z "$major" ] || [ "$major" -lt 17 ] 2>/dev/null; then
    echo "ERROR: No installed Java 17 found (detected: ${line:-unknown})." >&2
    echo "       Install JDK 17 or newer, then run this script again." >&2
    exit 1
  fi
}

check_spring_boot() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: No installed Spring Boot build tool found (Maven 'mvn' is not on your PATH)." >&2
    echo "       Install Maven 3.8 or newer, then run this script again." >&2
    exit 1
  fi

  if [ ! -f pom.xml ] || ! grep -q "spring-boot-starter-parent" pom.xml; then
    echo "ERROR: No installed Spring Boot project found (pom.xml with spring-boot-starter-parent is missing)." >&2
    echo "       Run this script from the spring-chat project folder." >&2
    exit 1
  fi

  echo "Checking Spring Boot Maven plugin (first run may download it) ..."
  if ! mvn -q -B -N org.springframework.boot:spring-boot-maven-plugin:help >/dev/null 2>&1; then
    echo "ERROR: Spring Boot is not installed or could not be loaded by Maven." >&2
    echo "       Check your internet connection and Maven settings, then try again." >&2
    exit 1
  fi
}

new_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# Sends one command line to the running server and prints its reply
send_to_server() {
  local line="$1" escaped response http_code body
  escaped="$(printf '%s' "$line" | sed 's/\\/\\\\/g; s/"/\\"/g')"

  response="$(curl -sS -w '\n%{http_code}' -X POST "http://localhost:${PORT}/api/admin/command" \
    -H "X-Admin-Token: ${TOKEN}" \
    -H 'Content-Type: application/json' \
    --data "{\"command\":\"${escaped}\"}")" || {
    echo "ERROR: Could not reach the server on port ${PORT}." >&2
    return 1
  }

  http_code="${response##*$'\n'}"
  body="${response%$'\n'*}"
  if [ "$http_code" != "200" ]; then
    echo "ERROR: ${body}" >&2
    return 1
  fi
  printf '%s\n' "$body"
}

wait_for_start() {
  local i
  for i in $(seq 1 180); do
    if grep -q "Started Main" "$LOG_FILE" 2>/dev/null; then
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      return 1
    fi
    sleep 1
  done
  return 1
}

shutdown_server() {
  trap - EXIT
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    send_to_server "stop" >/dev/null 2>&1 || true
    local i
    for i in $(seq 1 15); do
      kill -0 "$SERVER_PID" 2>/dev/null || break
      sleep 1
    done
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  if [ -n "$SERVER_PID" ]; then
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$STATE_FILE"
}

prompt_loop() {
  local line
  while true; do
    printf '> '
    if ! IFS= read -r line; then
      echo
      break
    fi
    line="$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    case "$line" in
      "")
        ;;
      "?")
        send_to_server "help" || true
        ;;
      exit|quit)
        break
        ;;
      stop)
        send_to_server "stop" || true
        break
        ;;
      *)
        send_to_server "$line" || true
        ;;
    esac
  done
}

start_server() {
  PORT="$1"

  check_java
  check_spring_boot

  if [ -f "$STATE_FILE" ]; then
    echo "ERROR: The server may already be running ($STATE_FILE exists)." >&2
    echo "       Stop it first, or delete $STATE_FILE if the server is not running." >&2
    exit 1
  fi

  TOKEN="$(new_token)"
  (umask 077 && printf 'PORT=%s\nTOKEN=%s\n' "$PORT" "$TOKEN" > "$STATE_FILE")

  trap shutdown_server EXIT
  trap 'exit 130' INT TERM

  echo "Starting Spring Chat on port ${PORT} (server output: ${LOG_FILE}) ..."
  mvn spring-boot:run \
    -Dspring-boot.run.arguments="--server.port=${PORT} --chat.admin-token=${TOKEN} --chat.console.enabled=false" \
    > "$LOG_FILE" 2>&1 &
  SERVER_PID=$!

  if ! wait_for_start; then
    echo "ERROR: The server did not start. See ${LOG_FILE} for details." >&2
    exit 1
  fi

  echo "Spring Chat is running at http://localhost:${PORT}"
  echo "Type a command at the prompt. Type 'help' for the list, 'exit' to stop the server."
  prompt_loop
}

case "${1:-start}" in
  help|-h|--help)
    usage
    ;;
  start)
    start_server "${2:-8080}"
    ;;
  *)
    if [[ "$1" =~ ^[0-9]+$ ]]; then
      start_server "$1"
    else
      echo "Unknown option: $1" >&2
      usage
      exit 1
    fi
    ;;
esac
