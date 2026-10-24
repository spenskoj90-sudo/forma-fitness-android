#!/usr/bin/env bash
# One-time owner setup; never run in CI. Creates a permanent FORMA-only
# release key outside the Git repository, with no password on the CLI.
set -euo pipefail
umask 077
if [[ $# -ne 1 || "$1" != /* ]]; then
  echo 'Usage: bash scripts/create-release-signer.sh /absolute/secure/path/forma-release.p12' >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PARENT="$(dirname "$1")"
mkdir -p -m 700 -- "$PARENT"
PARENT="$(cd "$PARENT" && pwd -P)"
if [[ "$PARENT" == "$ROOT" || "$PARENT" == "$ROOT/"* ]]; then
  echo 'Refusing to save a private signing key inside the FORMA source repository.' >&2
  exit 3
fi
DEST="$PARENT/$(basename "$1")"
if [[ -e "$DEST" ]]; then
  echo 'Refusing to overwrite an existing signing key.' >&2
  exit 4
fi
if ! command -v keytool >/dev/null 2>&1; then
  echo 'JDK keytool is required.' >&2
  exit 5
fi
echo 'Creating your permanent FORMA release signer. Enter a strong password when prompted.'
keytool -genkeypair -keystore "$DEST" -storetype PKCS12 -alias forma-prod \
  -keyalg RSA -keysize 4096 -validity 9125 \
  -dname 'CN=FORMA Release,O=FORMA'
chmod 600 "$DEST"
echo 'Record only the PUBLIC signing-certificate SHA-256 fingerprint:'
keytool -list -v -keystore "$DEST" -alias forma-prod | grep -i 'SHA256:'
printf '%s\n' \
  'Create TWO protected offline backups; losing this key prevents regular APK updates.' \
  'Keep the key and password out of Git, chat, documents and APK artifacts.' \
  'Configure the GitHub forma-release environment and four encrypted secrets manually.' \
  'This script never uploads or transmits the private key.'
