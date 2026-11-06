#!/usr/bin/env bash
# Runs inside the emulator step: installs the app and its tests, runs them, and
# collects the screenshots they save. Exits non-zero if any test failed or the
# app crashed.
set -uo pipefail

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb shell am instrument -w -r com.reactivewallpaper.test/androidx.test.runner.AndroidJUnitRunner \
    | tee device-output/instrumentation.txt

adb logcat -d > device-output/logcat.txt || true
adb exec-out run-as com.reactivewallpaper tar cf - -C files screens | tar xf - -C device-output || true
ls -la device-output/screens || true

if grep -q "FAILURES!!!\|Process crashed\|INSTRUMENTATION_FAILED" device-output/instrumentation.txt; then
  echo "::error::Device tests failed; see instrumentation.txt in the device-test-results artifact."
  exit 1
fi
grep -q "^OK (" device-output/instrumentation.txt
