#!/usr/bin/env bash
set -euo pipefail

test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
apk=.forma-emulator/update/app-v3.apk
test -s "$apk"
apksigner=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -1)
report=$("$apksigner" verify --verbose --print-certs "$apk")
grep -Fxq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<< "$report"
mapfile -t signers < <(awk '/^Signer #[0-9]+ certificate SHA-256 digest:/ {print $NF}' <<< "$report")
test "${#signers[@]}" = 1
[[ "${signers[0]}" =~ ^[0-9a-f]{64}$ ]]

stage=$(mktemp -d)
fixture_pid=
device_port=10443
cleanup() {
  adb reverse --remove "tcp:$device_port" >/dev/null 2>&1 || true
  if [[ -n "$fixture_pid" ]]; then
    kill "$fixture_pid" 2>/dev/null || true
    wait "$fixture_pid" 2>/dev/null || true
  fi
  rm -rf -- "$stage"
}
trap cleanup EXIT
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=localhost \
  -addext subjectAltName=DNS:localhost -keyout "$stage/tls.key" -out "$stage/tls.crt" 2>/dev/null
node scripts/update-https-fixture.mjs "$stage" "$apk" "${signers[0]}" &
fixture_pid=$!
for attempt in {1..50}; do
  [[ -s "$stage/port" ]] && break
  kill -0 "$fixture_pid"
  sleep 0.2
done
host_port=$(cat "$stage/port")
[[ "$host_port" =~ ^[0-9]+$ ]]
curl --noproxy '*' --fail --silent --show-error --max-time 5 --cacert "$stage/tls.crt" \
  "https://localhost:$host_port/stable.json" >/dev/null
adb reverse "tcp:$device_port" "tcp:$host_port"
tls=$(openssl x509 -in "$stage/tls.crt" -outform DER | base64 -w0)
timeout --signal=TERM --kill-after=15s 3m adb shell am instrument -w \
  -e class ru.forma.app.UpdateHttpsInstrumentedTest \
  -e formaUpdateFixturePort "$device_port" -e formaUpdateFixtureTls "$tls" \
  -e formaUpdateFixturePin "$(cat "$stage/pin")" \
  ru.forma.fitness.test/androidx.test.runner.AndroidJUnitRunner | tee verification/update-https-api34.txt
grep -Fx 'OK (2 tests)' verification/update-https-api34.txt
