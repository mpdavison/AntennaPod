#!/bin/bash
set -e
./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1
adb logcat -v time | grep -e Whisper -e AdDetect
