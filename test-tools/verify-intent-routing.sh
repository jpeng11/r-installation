#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
readonly APP_PACKAGE="dev.jpeng.rinstaller"
readonly APP_COMPONENT="${APP_PACKAGE}/.InstallActivity"
readonly FIXTURE_PACKAGE="dev.jpeng.rinstaller.fixture"
readonly FIXTURE_COMPONENT="${FIXTURE_PACKAGE}/.RoutingProbeActivity"
readonly TEST_URI="content://dev.jpeng.rinstaller.fixture.delayed/self.apk"
readonly ADB_BIN="${ADB:-adb}"

adb_command() {
    if [[ -n "${ANDROID_SERIAL:-}" ]]; then
        "${ADB_BIN}" -s "${ANDROID_SERIAL}" "$@"
    else
        "${ADB_BIN}" "$@"
    fi
}

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_contains() {
    local haystack="$1"
    local needle="$2"
    local context="$3"
    [[ "${haystack}" == *"${needle}"* ]] \
        || fail "${context}: expected to find '${needle}'"
}

query_route() {
    local action="$1"
    local mime_type="${2:-}"
    local -a arguments=(
        shell cmd package query-activities
        -a "${action}"
        -d "${TEST_URI}"
        --brief
    )
    if [[ -n "${mime_type}" ]]; then
        arguments+=( -t "${mime_type}" )
    fi
    adb_command "${arguments[@]}"
}

wait_for_resumed_activity() {
    local attempt
    local activities
    for attempt in {1..20}; do
        activities="$(adb_command shell dumpsys activity activities)"
        if [[ "${activities}" == *"${APP_COMPONENT}"* ]]; then
            return 0
        fi
        sleep 0.25
    done
    fail "${APP_COMPONENT} did not become the active activity"
}

dump_ui() {
    local output_path="/data/local/tmp/rinstaller-intent-routing.xml"
    adb_command shell uiautomator dump "${output_path}" >/dev/null
    adb_command exec-out cat "${output_path}"
}

readonly SERIAL="$(adb_command get-serialno)"
[[ -n "${SERIAL}" && "${SERIAL}" != "unknown" ]] || fail "no Android device is available"
readonly DEVICE_TYPE="$(adb_command shell getprop ro.kernel.qemu | tr -d '\r')"
if [[ "${DEVICE_TYPE}" != "1" && "${ALLOW_PHYSICAL_DEVICE:-0}" != "1" ]]; then
    fail "refusing to alter a physical device; use an emulator or set ALLOW_PHYSICAL_DEVICE=1"
fi

cd "${REPO_ROOT}"
./gradlew :app:assembleDebug :fixture-source:assembleDebug

adb_command install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb_command install -r fixture-source/build/outputs/apk/debug/fixture-source-debug.apk >/dev/null
adb_command shell pm clear "${APP_PACKAGE}" >/dev/null
adb_command shell cmd locale set-app-locales "${APP_PACKAGE}" --user 0 --locales en-US

route_output="$(query_route android.intent.action.INSTALL_PACKAGE)"
assert_contains "${route_output}" "${APP_COMPONENT}" "INSTALL_PACKAGE without MIME"

route_output="$(query_route android.intent.action.VIEW application/vnd.android.package-archive)"
assert_contains "${route_output}" "${APP_COMPONENT}" "VIEW with standard APK MIME"

for vendor_mime in \
    application/apk.1 \
    application/1 \
    application/octet-stream \
    application/zip
do
    route_output="$(query_route android.intent.action.VIEW "${vendor_mime}")"
    assert_contains "${route_output}" "${APP_COMPONENT}" "VIEW with ${vendor_mime}"
done

adb_command shell am force-stop "${APP_PACKAGE}"
adb_command shell am start -W \
    -n "${FIXTURE_COMPONENT}" \
    --es mode vendor-apk-one >/dev/null
wait_for_resumed_activity

ui_dump="$(dump_ui)"
assert_contains "${ui_dump}" "Confirmation is required for this request." \
    "untrusted app-store confirmation state"
assert_contains "${ui_dump}" "Allowlist: not trusted" \
    "untrusted source identity"
assert_contains "${ui_dump}" "Cancel" "confirmation controls"

echo "PASS: app-store intent routes and untrusted-source confirmation UI"
