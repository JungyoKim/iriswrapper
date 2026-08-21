#!/bin/bash
# Self-contained iris redeploy: stop -> backup -> swap APK -> start with logging.
# Runs detached on the host so a dropped SSH connection cannot leave iris stopped.
exec >> "$HOME/deploy.log" 2>&1
echo "================ deploy start $(date) ================"
APKDEV=/data/local/tmp/Iris.apk
LOG=/data/local/tmp/iris.log
PATCHED="$HOME/Iris-patched.apk"

find_pid() {
  adb shell 'ps -ef' 2>/dev/null \
    | grep "app_process / party.qwer.iris.Main" | grep -v "sh -c" \
    | awk '{print $2}' | head -1
}

OLD=$(find_pid)
echo "old iris pid: ${OLD:-NONE}"
if [ -n "$OLD" ]; then
  adb shell "su root sh -c 'kill -9 $OLD'" && echo "killed $OLD"
fi
# drop orphaned host-side adb shell wrappers from the previous launch
pkill -f "emulator-5554 shell su root" 2>/dev/null && echo "host wrapper cleared"
sleep 3

echo "backup current apk -> ${APKDEV}.bak"
adb shell "su root sh -c 'cp $APKDEV ${APKDEV}.bak'" && echo "backup ok"

echo "pushing patched apk"
adb push "$PATCHED" "$APKDEV"
echo "device apk md5: $(adb shell "su root sh -c 'md5sum $APKDEV'")"

adb shell "su root sh -c 'rm -f $LOG'"

echo "starting iris (detached, logging to $LOG)"
setsid nohup adb shell su root sh -c "CLASSPATH=$APKDEV app_process / party.qwer.iris.Main > $LOG 2>&1" </dev/null >/dev/null 2>&1 &
sleep 9

NEW=$(find_pid)
echo "new iris pid: ${NEW:-FAILED}"
echo "iris.log head:"
adb shell "su root sh -c 'head -25 $LOG'" 2>/dev/null
echo "================ deploy done $(date) ================"
