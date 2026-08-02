#!/bin/bash
# Runs the locally-runnable build steps from .github/workflows/checks.yml.
# Emulator execution is NOT included: it requires KVM + an Android emulator,
# which are not available on this machine. The androidTest APK build step is.
#
# Usage: ./checks.sh [step]
#   step: wrapper | xml | static | keystore | build | test | androidtest | all (default)

set -euo pipefail

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

STEP="${1:-all}"

run_wrapper() {
    echo "===== [wrapper-validation] Gradle Wrapper Validation ====="
    ./gradlew --version
}

run_xml() {
    echo "===== [static-analysis] XML code style ====="
    if [ ! -f android-xml-formatter.jar ]; then
        curl -s -L https://github.com/ByteHamster/android-xml-formatter/releases/download/1.1.0/android-xml-formatter.jar > android-xml-formatter.jar
    fi
    TMP_BACKUP=$(mktemp -d)
    find . -wholename "*/res/layout/*.xml" -print0 | while IFS= read -r -d '' f; do
        mkdir -p "$TMP_BACKUP/$(dirname "$f")"
        cp "$f" "$TMP_BACKUP/$f"
    done
    find . -wholename "*/res/layout/*.xml" -print0 | xargs -0 java -jar android-xml-formatter.jar
    CHANGED=0
    while IFS= read -r -d '' f; do
        if ! cmp -s "$f" "$TMP_BACKUP/$f"; then
            CHANGED=1
            echo "XML formatting violation: $f"
        fi
    done < <(find . -wholename "*/res/layout/*.xml" -print0)
    # restore pre-formatter state (never leave the tree modified)
    while IFS= read -r -d '' f; do
        cp "$TMP_BACKUP/$f" "$f"
    done < <(find . -wholename "*/res/layout/*.xml" -print0)
    rm -rf "$TMP_BACKUP"
    if [ "$CHANGED" -ne 0 ]; then
        echo "===== Found XML code style violations! ======"
        exit 1
    fi
    echo "XML code style OK"
}

run_static() {
    echo "===== [static-analysis] Checkstyle + Lint ====="
    ./gradlew checkstyle lint
}

run_keystore() {
    echo "===== [unit-test] Create temporary release keystore ====="
    if [ ! -f app/keystore ]; then
        keytool -noprompt -genkey -v -keystore "app/keystore" -alias alias -storepass password -keypass password -keyalg RSA -validity 10 -dname "CN=antennapod.org, OU=dummy, O=dummy, L=dummy, S=dummy, C=US"
    fi
    cp app/keystore app-wearos/keystore
}

run_build() {
    echo "===== [unit-test] Build: assemblePlayDebug, assemblePlayRelease, assembleFreeRelease ====="
    ./gradlew assemblePlayDebug assemblePlayRelease assembleFreeRelease
}

run_test() {
    echo "===== [unit-test] Test: PlayDebug/Debug + PlayRelease/Release ====="
    ./gradlew testPlayDebugUnitTest testDebugUnitTest testPlayReleaseUnitTest testReleaseUnitTest
}

run_androidtest() {
    echo "===== [emulator-test] Build androidTest APK: assemblePlayDebugAndroidTest ====="
    ./gradlew assemblePlayDebugAndroidTest
}

case "$STEP" in
    wrapper)   run_wrapper ;;
    xml)       run_xml ;;
    static)    run_static ;;
    keystore)  run_keystore ;;
    build)     run_keystore && run_build ;;
    test)      run_test ;;
    androidtest) run_androidtest ;;
    all)
        run_wrapper
        run_xml
        run_static
        run_keystore
        run_build
        run_test
        run_androidtest
        echo "===== All runnable checks passed. ====="
        ;;
    *) echo "Unknown step: $STEP" >&2; exit 1 ;;
esac
