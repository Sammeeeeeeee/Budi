# Runs as the shell user via Shizuku. Looks for anything outside the RFCOMM channel that
# reflects the noise control mode while Samsung's manager owns the buds.
P=com.samsung.accessory.paranmgr
K='buds|paran|earbud|noise.?control|ambient.?sound|(^|[^a-z])anc([^a-z]|$)'
s() { printf '\n===== %s\n' "$1"; }

s device
date
getprop ro.product.model
getprop ro.build.version.release
getprop ro.build.version.oneui
getprop ro.build.display.id

s packages
pm list packages --show-versioncode | grep -iE 'accessory|watchmanager|buds|wearable'

s 'manager package'
timeout 20 dumpsys package $P | head -n 800

s 'manager processes'
ps -A -o PID,USER,NAME | grep -iE 'accessory|buds|wearable'

s 'manager services'
timeout 20 dumpsys activity service $P | head -n 400

s 'manager providers'
timeout 20 dumpsys activity provider $P | head -n 200

s 'manager broadcasts'
timeout 20 dumpsys activity broadcasts $P history | head -n 400

s settings
for ns in global system secure; do settings list $ns | grep -iE "$K" | sed "s/^/$ns: /"; done

s bluetooth
BT=$(timeout 20 dumpsys bluetooth_manager)
echo "$BT" | grep -iE -A22 'metadata changes'
echo "$BT" | grep -iE "custom metadata|R630|$K" | head -n 100

s audio
timeout 20 dumpsys audio | grep -iE "$K" | head -n 100

s systemui
timeout 30 dumpsys activity service com.android.systemui | grep -iE -B2 -A4 "$K" | head -n 300

s 'logcat: manager'
for pid in $(pidof $P); do logcat -d -v threadtime --pid=$pid | tail -n 800; done

s 'logcat: keywords'
logcat -d -v threadtime | grep -iE "$K" | tail -n 300
