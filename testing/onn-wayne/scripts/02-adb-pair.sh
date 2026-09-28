#!/usr/bin/env bash
# Pairs ADB over Wi-Fi and writes the CONNECT port back into config/wayne.env.
#
#   ./02-adb-pair.sh              # full pair + connect (first time after a wipe)
#   ./02-adb-pair.sh --reconnect  # just re-read the connect port after a reboot
#
# The two ports are different numbers and the connect port changes on every
# reboot. That single fact is the most common failure in this whole process,
# so this script keeps wayne.env honest instead of asking you to remember.

source "$(dirname "$0")/lib.sh"
require_cmd adb "Install Android platform-tools 34+."
load_env

RECONNECT=0
[[ "${1:-}" == "--reconnect" ]] && RECONNECT=1

[[ -n "${DEVICE_IP:-}" ]] || die "DEVICE_IP not set in config/wayne.env"

if [[ $RECONNECT -eq 0 ]]; then
    pause_for_human "On the TV: Settings → System → Developer options → Wireless debugging
    → 'Pair device with pairing code'. Leave that dialog open."
    read -r -p "    PAIRING port (from the pairing dialog): " PAIR_PORT
    [[ "$PAIR_PORT" =~ ^[0-9]+$ ]] || die "Pairing port must be numeric."
    info "Running: adb pair ${DEVICE_IP}:${PAIR_PORT}   (enter the 6-digit code when asked)"
    adb pair "${DEVICE_IP}:${PAIR_PORT}" || die "Pairing failed. adb pair needs platform-tools 33+, and the PC must be on the same subnet as the stick."
    ok "Paired."
fi

pause_for_human "Back out to the main Wireless debugging screen — the port shown THERE
    is the connect port, and it is a different number from the pairing port."
read -r -p "    CONNECT port: " CONN_PORT
[[ "$CONN_PORT" =~ ^[0-9]+$ ]] || die "Connect port must be numeric."

adb connect "${DEVICE_IP}:${CONN_PORT}" || true
if ! adb -s "${DEVICE_IP}:${CONN_PORT}" get-state >/dev/null 2>&1; then
    info "Target came back offline — restarting the adb server and retrying."
    adb kill-server >/dev/null 2>&1 || true
    adb start-server >/dev/null 2>&1 || true
    adb connect "${DEVICE_IP}:${CONN_PORT}" || true
fi
adb -s "${DEVICE_IP}:${CONN_PORT}" get-state >/dev/null 2>&1 \
    || die "Still not connected to ${DEVICE_IP}:${CONN_PORT}. Re-check the port — it is not the pairing port."

# Persist the port so the later phases do not have to ask again.
if grep -q '^ADB_PORT=' "$CONFIG_DIR/wayne.env"; then
    sed -i.bak "s/^ADB_PORT=.*/ADB_PORT=\"$CONN_PORT\"/" "$CONFIG_DIR/wayne.env" && rm -f "$CONFIG_DIR/wayne.env.bak"
else
    echo "ADB_PORT=\"$CONN_PORT\"" >> "$CONFIG_DIR/wayne.env"
fi
ok "Connected. ADB_PORT=$CONN_PORT saved to config/wayne.env"

ADB_TARGET="${DEVICE_IP}:${CONN_PORT}"
echo
info "Build check (this is the gate that says whether unlock is still available):"
FP="$(getprop ro.build.fingerprint)"
echo "  $FP"
case "$FP" in
    *UKRB.251217.001.A1*) ok "Shipping build — unlockable and rootable. The OTA block held." ;;
    *UKRB.260113.027.A1*) warn "This is the first OTA build. If you had not already unlocked, unlock is gone." ;;
    *)                    warn "Unrecognised build. Nothing here is tested on it." ;;
esac
