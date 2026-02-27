#!/usr/bin/env bash
# reverse_8888.sh
# Enable or remove Android reverse port forwarding for tcp:8888.
# On 'connect', also set the device's global HTTP proxy to localhost:PORT.
# On 'disconnect', also remove the device's global HTTP proxy setting.
# Works both as a callback from adb_monitor and as a standalone command.
#
# Usage:
#   reverse_8888.sh [connect|disconnect] [-s SERIAL] [-p PORT] [--adb PATH]
# Defaults:
#   action: connect
#   port:   8888
#   adb:    adb from PATH, or $ADB if set
# Serial selection order:
#   1) -s/--serial flag
#   2) $ADB_SERIAL env var
#   3) For 'connect'/'disconnect' with no serial: if one or more emulators are
#      connected, apply the action to all of them.
#   4) Otherwise, if exactly one device is in 'device' state, pick it.
#   If still ambiguous, print a hint and exit with non-zero status.
#
# When called by adb_monitor, it can also read $ADB_MONITOR_EVENT for the action.

set -euo pipefail

ADB_BIN="${ADB:-adb}"
PORT="${PORT:-8888}"
SERIAL="${ADB_SERIAL:-}"
ACTION="${1:-}"

# If first arg is an action, shift it off. Otherwise we will derive from env or default.
case "${ACTION:-}" in
  connect|disconnect)
    shift
    ;;
  ""|help|-h|--help)
    # If ACTION is empty, try env var
    if [[ -n "${ADB_MONITOR_EVENT:-}" ]]; then
      ACTION="${ADB_MONITOR_EVENT}"
    else
      ACTION="connect"
    fi
    ;;
  *)
    # Not an action, treat as flags only
    ACTION="${ADB_MONITOR_EVENT:-connect}"
    ;;
esac

# Parse flags
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s|--serial)
      SERIAL="$2"; shift 2;;
    -p|--port)
      PORT="$2"; shift 2;;
    --adb)
      ADB_BIN="$2"; shift 2;;
    -h|--help)
      cat <<EOF
Usage: $0 [connect|disconnect] [-s SERIAL] [-p PORT] [--adb PATH]
Enable or remove 'adb reverse tcp:PORT tcp:PORT' for a device.
On 'connect', also sets the device's global HTTP proxy to localhost:PORT.
On 'disconnect', also removes the device's global HTTP proxy setting.
Examples:
  $0 connect
  $0 connect -p 8888
  $0 disconnect -s emulator-5554
  ADB_SERIAL=R3CN30 foo $0 connect
EOF
      exit 0
      ;;
    *)
      echo "[reverse-8888] Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

# Ensure adb server is up
"$ADB_BIN" start-server >/dev/null 2>&1 || true

pick_single_device() {
  # List only devices in 'device' state, ignore headers and offline/unauthorized
  local list
  # Output first column (serial) where second column equals 'device'
  list="$("$ADB_BIN" devices | awk 'NF==2 && $2=="device"{print $1}')"
  local count
  count="$(printf "%s\n" "$list" | sed '/^$/d' | wc -l | tr -d ' ')"
  if [[ "$count" -eq 1 ]]; then
    printf "%s" "$list" | sed '/^$/d' | head -n1
    return 0
  elif [[ "$count" -eq 0 ]]; then
    return 10
  else
    return 20
  fi
}

list_connected_emulators() {
  "$ADB_BIN" devices | awk 'NF==2 && $2=="device" && $1 ~ /^emulator-[0-9]+$/{print $1}'
}

resolve_single_device_or_exit() {
  if SERIAL="$(pick_single_device)"; then
    echo "[reverse-8888] Auto-selected device: ${SERIAL}"
    return 0
  fi

  local rc
  rc=$?
  if [[ $rc -eq 10 ]]; then
    echo "[reverse-8888] No connected devices in 'device' state. Plug a device or pass -s SERIAL." >&2
  else
    echo "[reverse-8888] Multiple devices connected. Pass -s SERIAL to disambiguate." >&2
    "$ADB_BIN" devices
  fi
  exit 2
}

enable_reverse() {
  if "$ADB_BIN" -s "$SERIAL" reverse --list 2>/dev/null | grep -qE "^tcp:${PORT}[[:space:]]+tcp:${PORT}$"; then
    echo "[reverse-8888] Already enabled for ${SERIAL} on tcp:${PORT}"
    return 0
  fi
  "$ADB_BIN" -s "$SERIAL" reverse "tcp:${PORT}" "tcp:${PORT}"
  echo "[reverse-8888] Enabled reverse tcp:${PORT} <-> tcp:${PORT} for ${SERIAL}"
}

set_device_proxy() {
  local desired_proxy
  desired_proxy="localhost:${PORT}"

  # Best-effort read/compare to avoid unnecessary writes.
  local current_proxy
  current_proxy="$("$ADB_BIN" -s "$SERIAL" shell settings get global http_proxy 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  if [[ "$current_proxy" == "$desired_proxy" ]]; then
    echo "[reverse-8888] Device proxy already set to ${desired_proxy} for ${SERIAL}"
    return 0
  fi

  "$ADB_BIN" -s "$SERIAL" shell settings put global http_proxy "$desired_proxy"

  # Verify (some devices/OS builds silently reject the write).
  current_proxy="$("$ADB_BIN" -s "$SERIAL" shell settings get global http_proxy 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  if [[ "$current_proxy" != "$desired_proxy" ]]; then
    echo "[reverse-8888] Failed to set device proxy to ${desired_proxy} (got '${current_proxy:-<empty>}') for ${SERIAL}" >&2
    return 1
  fi

  echo "[reverse-8888] Device proxy set to ${desired_proxy} for ${SERIAL}"
}

clear_device_proxy() {
  local current_proxy
  local current_host
  local current_port
  current_proxy="$("$ADB_BIN" -s "$SERIAL" shell settings get global http_proxy 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  current_host="$("$ADB_BIN" -s "$SERIAL" shell settings get global global_http_proxy_host 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  current_port="$("$ADB_BIN" -s "$SERIAL" shell settings get global global_http_proxy_port 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"

  # Common "no proxy" representations across Android builds.
  if [[ ( -z "$current_proxy" || "$current_proxy" == "null" || "$current_proxy" == ":0" ) \
     && ( -z "$current_host" || "$current_host" == "null" ) \
     && ( -z "$current_port" || "$current_port" == "null" || "$current_port" == "0" || "$current_port" == "-1" ) ]]; then
    echo "[reverse-8888] Device proxy already cleared for ${SERIAL}"
    return 0
  fi

  # Some Android builds keep host/port keys even after http_proxy is removed.
  "$ADB_BIN" -s "$SERIAL" shell settings delete global http_proxy >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SERIAL" shell settings put global http_proxy ":0" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SERIAL" shell settings delete global global_http_proxy_host >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SERIAL" shell settings delete global global_http_proxy_port >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SERIAL" shell settings delete global global_http_proxy_exclusion_list >/dev/null 2>&1 || true

  current_proxy="$("$ADB_BIN" -s "$SERIAL" shell settings get global http_proxy 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  current_host="$("$ADB_BIN" -s "$SERIAL" shell settings get global global_http_proxy_host 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  current_port="$("$ADB_BIN" -s "$SERIAL" shell settings get global global_http_proxy_port 2>/dev/null | tr -d '\r' | tr -d '\n' || true)"
  if [[ ( -z "$current_proxy" || "$current_proxy" == "null" || "$current_proxy" == ":0" ) \
     && ( -z "$current_host" || "$current_host" == "null" ) \
     && ( -z "$current_port" || "$current_port" == "null" || "$current_port" == "0" || "$current_port" == "-1" ) ]]; then
    echo "[reverse-8888] Device proxy cleared for ${SERIAL}"
    return 0
  fi

  echo "[reverse-8888] Failed to clear device proxy settings (http_proxy='${current_proxy}', host='${current_host}', port='${current_port}') for ${SERIAL}" >&2
  return 1
}

remove_reverse() {
  "$ADB_BIN" -s "$SERIAL" reverse --remove "tcp:${PORT}" >/dev/null 2>&1 || true
  echo "[reverse-8888] Removal attempted for ${SERIAL} on tcp:${PORT}"
}

case "$ACTION" in
  connect)
    if [[ -n "$SERIAL" ]]; then
      enable_reverse
      set_device_proxy
      exit 0
    fi

    # If no serial is provided, connect all connected emulators.
    set -- $(list_connected_emulators)
    emulator_count="$#"
    if [[ "$emulator_count" -gt 0 ]]; then
      echo "[reverse-8888] Connecting ${emulator_count} emulator(s) on tcp:${PORT}"
      failures=0
      for emulator_serial in "$@"; do
        SERIAL="$emulator_serial"
        enable_reverse || failures=1
        set_device_proxy || failures=1
      done

      if [[ "$failures" -ne 0 ]]; then
        echo "[reverse-8888] Connect finished with errors on one or more emulators." >&2
        exit 1
      fi
      exit 0
    fi

    # Backward-compatible fallback if no emulators are connected.
    resolve_single_device_or_exit
    enable_reverse
    set_device_proxy
    ;;
  disconnect)
    if [[ -n "$SERIAL" ]]; then
      remove_reverse
      clear_device_proxy
      exit 0
    fi

    # If no serial is provided, disconnect all connected emulators.
    set -- $(list_connected_emulators)
    emulator_count="$#"
    if [[ "$emulator_count" -gt 0 ]]; then
      echo "[reverse-8888] Disconnecting ${emulator_count} emulator(s) on tcp:${PORT}"
      failures=0
      for emulator_serial in "$@"; do
        SERIAL="$emulator_serial"
        remove_reverse || failures=1
        clear_device_proxy || failures=1
      done

      if [[ "$failures" -ne 0 ]]; then
        echo "[reverse-8888] Disconnect finished with errors on one or more emulators." >&2
        exit 1
      fi
      exit 0
    fi

    # Backward-compatible fallback if no emulators are connected.
    resolve_single_device_or_exit
    remove_reverse
    clear_device_proxy
    ;;
  *)
    echo "[reverse-8888] Unknown action: $ACTION. Use connect or disconnect." >&2
    exit 2
    ;;
esac
