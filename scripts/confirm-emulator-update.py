#!/usr/bin/env python3
"""Accept FORMA's verified system update only on the disposable emulator."""
import re
import subprocess
import time


PACKAGE = 'ru.forma.fitness'
INSTALLER_FOCUS = re.compile(
    r'^\s*mCurrentFocus=Window\{[^}\n]+ '
    r'(?:com\.android\.packageinstaller|com\.google\.android\.packageinstaller)'
    r'/(?:com\.android\.packageinstaller)?\.PackageInstallerActivity\s*\}',
    re.M,
)


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=10)


def installed_version():
    packages = adb('shell', 'pm', 'list', 'packages', '--show-versioncode', PACKAGE)
    match = re.findall(r'^package:ru\.forma\.fitness versionCode:(\d+)\s*$', packages, re.M)
    if len(match) != 1:
        raise ValueError('Could not read one installed FORMA version')
    return int(match[0])


def main():
    if (not adb('get-serialno').strip().startswith('emulator-')
            or adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '34'):
        raise ValueError('Expected the disposable API 34 emulator')

    # Android instrumentation read the FORMA update prompt and the exact
    # visible Update button before leaving the system dialog open.
    point = adb('shell', 'run-as', PACKAGE, 'cat', 'files/test-installer-confirmation.txt').strip()
    if not re.fullmatch(r'[1-9]\d{0,3} [1-9]\d{0,3}', point):
        raise ValueError('Invalid coordinates from the observed system Update button')
    if installed_version() != 2:
        raise ValueError('Version changed before system confirmation')
    if not INSTALLER_FOCUS.search(adb('shell', 'dumpsys', 'window', 'windows')):
        raise ValueError('The Android PackageInstaller confirmation window lost focus')
    print('PASS Android confirmation visible while versionCode is still 2', flush=True)
    adb('shell', 'input', 'tap', *point.split())

    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if installed_version() == 3:
            print('PASS system confirmation installed versionCode 3', flush=True)
            adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            return
        time.sleep(1)
    raise ValueError('Android did not install versionCode 3 after confirming')


if __name__ == '__main__':
    main()
