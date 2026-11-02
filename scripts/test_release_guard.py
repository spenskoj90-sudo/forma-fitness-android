import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

try:
    import release_guard as guard
except ImportError:
    guard = None

PIN = 'a13104307fbd01a38a4e1fbdf7aafdf3f2e76be7cd18a1ac986a9b775d35cf7b'
REPORT = ('Verified using v2 scheme (APK Signature Scheme v2): true\n'
          'Number of signers: 1\nSigner #1 certificate DN: CN=PRIVATE_TEST_ONLY\n'
          'Signer #1 certificate SHA-256 digest: ' + PIN + '\n')
BADGING = "package: name='ru.forma.fitness' versionCode='3' versionName='0.3.0' platformBuildVersionName='x'\n"


class ReleaseGuardTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(guard, 'release guard is not implemented')

    def test_first_release_needs_explicit_approval(self):
        self.assertEqual(guard.validate_release('3', '0', 'true', PIN), 3)
        with self.assertRaises(ValueError):
            guard.validate_release('3', '0', 'false', PIN)

    def test_update_is_strict_and_cannot_reset_lineage(self):
        self.assertEqual(guard.validate_release('4', '3', 'false', PIN), 4)
        for value, last, first in [('3','3','false'), ('2','3','false'), ('4','3','true')]:
            with self.subTest(value=value,last=last,first=first), self.assertRaises(ValueError):
                guard.validate_release(value,last,first,PIN)

    def test_overflow_noncanonical_and_android_bounds(self):
        for value in ['18446744073709551619','2100000001','03','0','-1','3.0','3\n','٣','', '9'*10000]:
            with self.subTest(value=value[:24]), self.assertRaises(ValueError):
                guard.validate_release(value,'0','true',PIN)
        self.assertEqual(guard.validate_release('2100000000','2099999999','false',PIN),2100000000)
        for baseline in ['', '00', '-1', '2100000001']:
            with self.subTest(baseline=baseline), self.assertRaises(ValueError):
                guard.validate_release('3',baseline,'true',PIN)

    def test_protected_pin_is_mandatory(self):
        for pin in ['', 'a'*63, 'g'*64, PIN+'\n']:
            with self.subTest(pin=pin), self.assertRaises(ValueError):
                guard.validate_release('3','0','true',pin)

    def test_valid_report_returns_only_matching_signer(self):
        self.assertEqual(guard.verify_report(REPORT,PIN),PIN)

    def test_false_missing_duplicate_v2_or_multiple_signers_rejected(self):
        for report in [REPORT.replace(': true',': false'), REPORT.replace('Verified using v2 scheme (APK Signature Scheme v2): true\n',''), REPORT+'Verified using v2 scheme (APK Signature Scheme v2): false\n', REPORT.replace('Number of signers: 1','Number of signers: 2'), REPORT+('Signer #2 certificate SHA-256 digest: '+PIN+'\n'), REPORT.replace(PIN,'b'*64)]:
            with self.subTest(report=report[:100]), self.assertRaises(ValueError):
                guard.verify_report(report,PIN)

    def test_metadata_is_allowlisted_and_identity_is_exact(self):
        result=guard.apk_metadata(BADGING,REPORT,PIN,'3','0.3.0',b'abc')
        self.assertEqual(result,{'package':'ru.forma.fitness','versionCode':3,'versionName':'0.3.0','signerSha256':PIN,'v2':True,'apkSha256':'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad','sizeBytes':3})
        self.assertNotIn('PRIVATE_TEST_ONLY',str(result))
        for badging in [BADGING.replace('ru.forma.fitness','ru.forma.other'), BADGING.replace("versionCode='3'","versionCode='4'"), BADGING+BADGING, BADGING+'application-debuggable\n']:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                guard.apk_metadata(badging,REPORT,PIN,'3','0.3.0',b'abc')

    def test_failed_external_verifier_never_accepts_valid_stdout(self):
        with tempfile.TemporaryDirectory() as tmp:
            tool=Path(tmp)/'failed-tool'
            tool.write_text('#!/bin/sh\nprintf success\nexit 1\n');tool.chmod(0o700)
            with self.assertRaises(ValueError): guard.tool_output([str(tool)])

    def test_real_synthetic_keystore_pin_and_wrong_password(self):
        with tempfile.TemporaryDirectory(dir='/dev/shm') as tmp:
            path=Path(tmp)/'synthetic.p12'
            env=os.environ.copy();env['FORMA_GUARD_TEST_PASSWORD']='synthetic-test-only-123'
            cmd=['keytool','-genkeypair','-keystore',str(path),'-storetype','PKCS12','-alias','test-only','-keyalg','RSA','-keysize','2048','-dname','CN=synthetic-only','-validity','1','-storepass:env','FORMA_GUARD_TEST_PASSWORD']
            subprocess.run(cmd,env=env,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            der=subprocess.run(['keytool','-exportcert','-keystore',str(path),'-alias','test-only','-storepass:env','FORMA_GUARD_TEST_PASSWORD'],env=env,check=True,capture_output=True).stdout
            pin=hashlib.sha256(der).hexdigest()
            self.assertEqual(guard.verify_keystore(path,'test-only','FORMA_GUARD_TEST_PASSWORD',pin,env),pin)
            with self.assertRaises(ValueError):guard.verify_keystore(path,'test-only','FORMA_GUARD_TEST_PASSWORD','b'*64,env)
            env['FORMA_GUARD_TEST_PASSWORD']='wrong'
            with self.assertRaises(ValueError):guard.verify_keystore(path,'test-only','FORMA_GUARD_TEST_PASSWORD',pin,env)


if __name__=='__main__':unittest.main()
