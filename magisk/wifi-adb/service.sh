#!/system/bin/sh

# 仅映射本机 Wi-Fi 地址的指定端口；原生 adbd 继续负责 TLS 和配对认证。
MODDIR=${0%/*}
ADB_CHAIN=PAD_ADB_23333
ADB_PORT=23333
ADB_LOCK=/dev/pad-adb-tls-23333.lock
adb_last_state=
adb_last_network=
adb_rule_active=0

exec >/dev/null 2>&1
umask 077

adb_disabled() {
    [ -f "$MODDIR/disable" ] || [ -f "$MODDIR/remove" ] || [ ! -d "$MODDIR" ]
}

adb_iptables() {
    /system/bin/iptables -w 2 -t nat "$@"
}

adb_state() {
    [ "$adb_last_state" = "$1" ] && return
    adb_last_state=$1
    printf '%s\n' "$1" > "$MODDIR/state"
}

adb_clear_rule() {
    if [ "$adb_rule_active" = 1 ]; then
        adb_iptables -F "$ADB_CHAIN"
        adb_rule_active=0
    fi
}

adb_cleanup() {
    # 只删除本脚本的链和入口，不改系统或其他模块的规则。
    while adb_iptables -C PREROUTING -j "$ADB_CHAIN"; do
        adb_iptables -D PREROUTING -j "$ADB_CHAIN" || break
    done
    adb_iptables -F "$ADB_CHAIN"
    adb_iptables -X "$ADB_CHAIN"
    [ -d "$MODDIR" ] && adb_state stopped
    rm -f "$ADB_LOCK/pid"
    rmdir "$ADB_LOCK"
}

adb_disabled && exit 0
if ! mkdir "$ADB_LOCK"; then
    adb_old_pid=$(cat "$ADB_LOCK/pid" 2>/dev/null)
    case "$adb_old_pid" in
        ''|*[!0-9]*) ;;
        *)
            if kill -0 "$adb_old_pid" 2>/dev/null &&
                    tr '\000' ' ' < "/proc/$adb_old_pid/cmdline" | grep -Fq "$MODDIR/service.sh"; then
                exit 0
            fi
            ;;
    esac
    rm -f "$ADB_LOCK/pid"
    rmdir "$ADB_LOCK"
    mkdir "$ADB_LOCK" || exit 1
fi
printf '%s\n' "$$" > "$ADB_LOCK/pid"
trap 'exit 0' INT TERM HUP
trap adb_cleanup EXIT

adb_state waiting-for-boot
while [ "$(/system/bin/getprop sys.boot_completed)" != 1 ]; do
    adb_disabled && exit 0
    sleep 2
done

while ! adb_disabled; do
    adb_iface=$(/system/bin/getprop wifi.interface)
    [ -n "$adb_iface" ] || adb_iface=wlan0
    adb_ip=$(/system/bin/ip -o -4 addr show dev "$adb_iface" scope global 2>/dev/null |
        awk 'NR == 1 {split($4, a, "/"); print a[1]}')

    if [ -z "$adb_ip" ]; then
        adb_clear_rule
        adb_last_network=
        adb_state waiting-for-wifi
        sleep 5
        continue
    fi

    adb_network="$adb_iface:$adb_ip"
    if [ "$adb_network" != "$adb_last_network" ]; then
        adb_last_network=$adb_network
        # 每次接入只请求一次，继续由系统判断网络是否受信任，避免反复弹确认框。
        if [ "$(/system/bin/settings get global adb_wifi_enabled)" != 1 ]; then
            /system/bin/settings put global adb_wifi_enabled 1
        fi
    fi

    adb_tls=$(/system/bin/getprop service.adb.tls.port)
    case "$adb_tls" in
        ''|*[!0-9]*) adb_tls=0 ;;
    esac
    if [ "$(/system/bin/settings get global adb_wifi_enabled)" != 1 ] ||
            [ "$adb_tls" -lt 1024 ] || [ "$adb_tls" -gt 65535 ]; then
        adb_clear_rule
        adb_state waiting-for-native-tls
        sleep 5
        continue
    fi

    adb_iptables -N "$ADB_CHAIN" 2>/dev/null
    if [ "$adb_tls" = "$ADB_PORT" ]; then
        adb_clear_rule
    elif ! adb_iptables -C "$ADB_CHAIN" -i "$adb_iface" -d "$adb_ip/32" -p tcp \
            --dport "$ADB_PORT" -j REDIRECT --to-ports "$adb_tls"; then
        adb_iptables -F "$ADB_CHAIN"
        if ! adb_iptables -A "$ADB_CHAIN" -i "$adb_iface" -d "$adb_ip/32" -p tcp \
                --dport "$ADB_PORT" -j REDIRECT --to-ports "$adb_tls"; then
            adb_state redirect-unavailable
            sleep 10
            continue
        fi
    fi
    adb_rule_active=1
    if ! adb_iptables -C PREROUTING -j "$ADB_CHAIN"; then
        adb_iptables -A PREROUTING -j "$ADB_CHAIN"
    fi
    adb_state "ready $adb_ip:$ADB_PORT -> native-tls:$adb_tls"
    sleep 10
done
