#!/bin/bash
set -e
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
# ./gradlew --console=plain test
package_name=com.onesixeight.antennapod.debug
current_version_code=$(awk '/versionCode/ { print $2; exit }' app/build.gradle)
./gradlew --console=plain :app:assembleDebug
set +e
install_output=$(./gradlew --console=plain :app:installPlayDebug 2>&1)
install_status=$?
set -e
if [[ $install_status -ne 0 ]]; then
    installed_version_code=$(adb shell dumpsys package "$package_name" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | tr -d '\r' | head -n1)
    if [[ -n "$installed_version_code" && "$installed_version_code" != "$current_version_code" ]]; then
        adb uninstall "$package_name"
        ./gradlew --console=plain :app:installPlayDebug
    elif [[ "$install_output" == *"INSTALL_FAILED_UPDATE_INCOMPATIBLE"* ]]; then
        adb uninstall "$package_name"
        ./gradlew --console=plain :app:installPlayDebug
    else
        printf '%s\n' "$install_output"
        exit 1
    fi
fi
adb shell monkey -p "$package_name" 1

# grep for "AndroidRuntime" or "de.danoeh.antennapod" to see the logs of the app
adb logcat -v time | grep --line-buffered -E "com\.onesixeight\.antennapod\.debug|AndroidRuntime|FATAL EXCEPTION|W/System\.err|IllegalStateException|NullPointerException|IndexOutOfBoundsException|FragmentManager|MainActivity|ItemPagerFragment|EpisodeItemListAdapter"

# grep --line-buffered "AndroidRuntime\|de.danoeh.antennapod"
