#!/usr/bin/env python3
"""FORMA release checks. Stdlib only; never emit tool diagnostics or secrets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

DECIMAL = re.compile(r'(?:0|[1-9][0-9]{0,9})', re.ASCII)
PIN = re.compile(r'[0-9a-fA-F]{64}', re.ASCII)
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-.][0-9A-Za-z.-]+)?', re.ASCII)


def pin_value(value):
    if not PIN.fullmatch(value):
        raise ValueError('Missing or malformed protected certificate pin')
    return value.lower()


def decimal(value):
    if not DECIMAL.fullmatch(value) or int(value) > 2100000000:
        raise ValueError('Invalid Android version ledger value')
    return int(value)


def validate_release(version, baseline, first, pin):
    pin_value(pin)
    code, last = decimal(version), decimal(baseline)
    if code <= last or first not in ('true', 'false'):
        raise ValueError('Version must increase with an explicit first-use flag')
    if (last == 0) != (first == 'true'):
        raise ValueError('First-use approval must match the zero baseline only')
    return code


def verify_report(report, pin):
    expected = pin_value(pin)
    v2 = re.findall(r'^Verified using v2 scheme \(APK Signature Scheme v2\): (true|false)$', report, re.M)
    count = re.findall(r'^Number of signers: ([0-9]+)$', report, re.M)
    signers = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: (\S+)$', report, re.M)
    if v2 != ['true'] or count != ['1'] or len(signers) != 1:
        raise ValueError('Exactly one signer and verified v2=true are required')
    if pin_value(signers[0]) != expected:
        raise ValueError('APK certificate does not match protected pin')
    return expected


def tool_output(command, env=None):
    try:
        result = subprocess.run(command, env=env, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError('Verification tool unavailable or timed out') from None
    if result.returncode:
        raise ValueError('Verification tool failed')
    return result.stdout


def verify_keystore(path, alias, password_variable, pin, env=None):
    expected = pin_value(pin)
    environment = os.environ if env is None else env
    if not alias or not environment.get(password_variable):
        raise ValueError('Missing protected keystore credentials')
    der = tool_output(['keytool', '-exportcert', '-keystore', str(path), '-alias', alias,
                       '-storepass:env', password_variable], env=environment)
    if not der or hashlib.sha256(der).hexdigest() != expected:
        raise ValueError('Keystore certificate does not match protected pin')
    return expected


def apk_metadata(badging, report, pin, code, name, apk):
    if decimal(code) == 0 or not VERSION.fullmatch(name):
        raise ValueError('Invalid requested APK version')
    found = re.findall(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging, re.M)
    if found != [('ru.forma.fitness', code, name)] or 'application-debuggable' in badging:
        raise ValueError('APK package/version/debuggable mismatch')
    signer = verify_report(report, pin)
    return {'package': 'ru.forma.fitness', 'versionCode': int(code), 'versionName': name,
            'signerSha256': signer, 'v2': True, 'apkSha256': hashlib.sha256(apk).hexdigest(),
            'sizeBytes': len(apk)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['preflight', 'keystore', 'apk'])
    parser.add_argument('--keystore', type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--apksigner')
    parser.add_argument('--aapt')
    args = parser.parse_args()
    env = os.environ
    try:
        pin = env.get('FORMA_EXPECTED_SIGNER_SHA256', '')
        if args.mode == 'preflight':
            validate_release(env.get('VERSION_CODE', ''), env.get('FORMA_LAST_SIGNED_VERSION_CODE', ''),
                             env.get('FIRST_RELEASE_APPROVED', ''), pin)
            if not VERSION.fullmatch(env.get('VERSION_NAME', '')):
                raise ValueError('Invalid version name')
            print('FORMA release preflight PASS')
        elif args.mode == 'keystore':
            if args.keystore is None:
                raise ValueError('Missing keystore path')
            verify_keystore(args.keystore, env.get('FORMA_RELEASE_KEY_ALIAS', ''),
                            'FORMA_RELEASE_STORE_PASSWORD', pin)
            print('FORMA keystore certificate PASS')
        else:
            if args.apk is None or not args.apksigner or not args.aapt:
                raise ValueError('Missing APK verification tools')
            report = tool_output([args.apksigner, 'verify', '--verbose', '--print-certs', str(args.apk)]).decode('utf-8')
            badging = tool_output([args.aapt, 'dump', 'badging', str(args.apk)]).decode('utf-8')
            metadata = apk_metadata(badging, report, pin, env.get('VERSION_CODE', ''),
                                    env.get('VERSION_NAME', ''), args.apk.read_bytes())
            print(json.dumps(metadata, sort_keys=True))
    except (ValueError, OSError, UnicodeError):
        # Tool diagnostics may contain passwords, aliases, certificate DNs or device data.
        raise SystemExit('FORMA release guard FAIL') from None


if __name__ == '__main__':
    main()
