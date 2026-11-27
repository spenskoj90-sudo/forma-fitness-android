#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'HTTPS fixture: %s\n' "$1" >&2; exit 1; }

serial=$(adb get-serialno)
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
[[ "$serial" == emulator-* && "$api" == 34 ]] || fail 'expected the disposable API 34 emulator'
apk=.forma-emulator/update/app-v3.apk
test -s "$apk" || fail 'signed test APK is missing'
apksigner="$ANDROID_HOME/build-tools/34.0.0/apksigner"
test -x "$apksigner" || fail 'Android build-tools 34.0.0 is missing apksigner'
report=$("$apksigner" verify --verbose --print-certs "$apk") || fail 'apksigner rejected the test APK'
grep -Fxq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$report" || fail 'APK signature scheme v2 was not verified'
mapfile -t signers < <(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print $NF}' <<< "$report")
test "${#signers[@]}" = 1 || fail 'expected exactly one test APK signer'
[[ "${signers[0]}" =~ ^[0-9a-f]{64}$ ]] || fail 'invalid test APK certificate digest'
printf 'HTTPS fixture: verified one signer and v2 APK signature\n'

stage=$(mktemp -d)
fixture_pid=
device_port=10443
cleanup() {
  adb shell cmd appops set --user 0 ru.forma.fitness REQUEST_INSTALL_PACKAGES default >/dev/null 2>&1 || true
  adb reverse --remove "tcp:$device_port" >/dev/null 2>&1 || true
  if [[ -n "$fixture_pid" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
  fi
  rm -rf -- "$stage"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost -keyout "$stage/tls.key" -out "$stage/tls.crt" \
  2>"$stage/openssl.log" || { cat "$stage/openssl.log" >&2; fail 'could not create a disposable TLS certificate'; }
node scripts/update-https-fixture.mjs "$stage" "$apk" "${signers[0]}" &
fixture_pid=$!
for attempt in {1..50}; do
  [[ -s "$stage/port" ]] && break
  kill -0 "$fixture_pid" || fail 'TLS server exited before binding'
  sleep 0.2
done
test -s "$stage/port" && test -s "$stage/pin" || fail 'TLS server did not report readiness'
host_port=$(cat "$stage/port")
[[ "$host_port" =~ ^[0-9]+$ ]] || fail 'TLS server returned an invalid port'
curl --noproxy '*' --fail --silent --show-error --max-time 5 --cacert "$stage/tls.crt" \
  "https://localhost:$host_port/stable.json" >/dev/null || fail 'host loopback TLS check failed'
adb reverse "tcp:$device_port" "tcp:$host_port" || fail 'ADB did not map the emulator HTTPS port'
printf 'HTTPS fixture: host feed verified, starting Android assertions\n'
tls=$(openssl x509 -in "$stage/tls.crt" -outform DER | base64 -w0)
fixture_args=(
  -e formaUpdateFixturePort "$device_port" -e formaUpdateFixtureTls "$tls" \
  -e formaUpdateFixturePin "$(cat "$stage/pin")"
)
timeout --signal=TERM --kill-after=15s 3m adb shell am instrument -w \
  -e class ru.forma.app.UpdateHttpsInstrumentedTest "${fixture_args[@]}" \
  ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee verification/update-https-api34.txt
grep -Fx 'OK (2 tests)' verification/update-https-api34.txt

# Let the app request installation. The emulator must still show its own
# confirmation before the host test can accept the verified APK.
adb shell cmd appops set --user 0 ru.forma.fitness REQUEST_INSTALL_PACKAGES allow
evidence=verification/update-installer-api34.txt
timeout --signal=TERM --kill-after=15s 2m adb shell am instrument -w \
  -e class ru.forma.app.UpdateInstallerHandoffTest "${fixture_args[@]}" \
  ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee "$evidence"
grep -Fx 'OK (1 test)' "$evidence"
python3 scripts/confirm-emulator-update.py | tee -a "$evidence"
timeout --signal=TERM --kill-after=15s 2m adb shell am instrument -w \
  -e class ru.forma.app.AfterSystemUpdateTest \
  ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee -a "$evidence"
test "$(grep -Fxc 'OK (1 test)' "$evidence")" = 2
