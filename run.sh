#!/bin/bash
set -e
# ./gradlew --console=plain test
./gradlew --console=plain :app:assembleDebug
if ! ./gradlew --console=plain :app:installPlayDebug; then
    adb uninstall de.danoeh.antennapod.debug
    ./gradlew --console=plain :app:installPlayDebug
fi
adb shell monkey -p de.danoeh.antennapod.debug 1

# grep for "AndroidRuntime" or "de.danoeh.antennapod" to see the logs of the app
adb logcat -v time | tee >(grep --line-buffered "AndroidRuntime\|de.danoeh.antennapod" >&2)
# grep --line-buffered "AndroidRuntime\|de.danoeh.antennapod"
