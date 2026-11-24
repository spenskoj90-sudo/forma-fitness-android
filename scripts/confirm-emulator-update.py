#!/usr/bin/env python3
"""Accept FORMA's verified system update only on the disposable emulator."""
import re
import subprocess
import time
from xml.etree import ElementTree


PACKAGE = 'ru.forma.fitness'
INSTALLER_PACKAGES = {'com.android.packageinstaller', 'com.google.android.packageinstaller'}
DUMP = '/data/local/tmp/forma-test-install-dialog.xml'


def adb(*args):
    return subprocess.check_output(['adb', *args], text=True, timeout=10)


def installed_version():
    packages = adb('shell', 'pm', 'list', 'packages', '--show-versioncode', PACKAGE)
    match = re.findall(r'^package:ru\.forma\.fitness versionCode:(\d+)\s*$', packages, re.M)
    if len(match) != 1:
        raise ValueError('Could not read one installed FORMA version')
    return int(match[0])


def confirmation_center(xml):
    nodes = list(ElementTree.fromstring(xml).iter('node'))
    for package in INSTALLER_PACKAGES:
        controls = [node for node in nodes if node.get('package') == package]
        labels = {node.get('text') for node in controls}
        if not {'FORMA', 'Do you want to update this app?'} <= labels:
            continue
        buttons = [node for node in controls
                   if node.get('enabled') == 'true' and node.get('clickable') == 'true']
        if not any(node.get('text') == 'Cancel' for node in buttons):
            continue
        update = [node for node in buttons if node.get('text') == 'Update']
        if len(update) != 1:
            continue
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', update[0].get('bounds', ''))
        if bounds:
            left, top, right, bottom = map(int, bounds.groups())
            if left < right and top < bottom:
                return (left + right) // 2, (top + bottom) // 2
    return None


def require_visible_confirmation(expected):
    # ActivityScenario may temporarily bring its cleanup Activity to the front.
    # Re-read the active accessibility tree after that transition. DUMP is
    # removed before every read so a failed dump cannot reuse stale evidence.
    deadline = time.monotonic() + 30
    try:
        while time.monotonic() < deadline:
            adb('shell', 'rm', '-f', DUMP)
            try:
                adb('shell', 'uiautomator', 'dump', DUMP)
                point = confirmation_center(adb('exec-out', 'cat', DUMP))
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ElementTree.ParseError):
                point = None
            if point == expected:
                return
            time.sleep(1)
    finally:
        adb('shell', 'rm', '-f', DUMP)
    raise ValueError('The active FORMA update dialog did not match its observed Update button')


def main():
    if (not adb('get-serialno').strip().startswith('emulator-')
            or adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '34'):
        raise ValueError('Expected the disposable API 34 emulator')

    # Android instrumentation read the FORMA update prompt and the exact
    # visible Update button before leaving the system dialog open.
    point = adb('shell', 'run-as', PACKAGE, 'cat', 'files/test-installer-confirmation.txt').strip()
    if not re.fullmatch(r'[1-9]\d{0,3} [1-9]\d{0,3}', point):
        raise ValueError('Invalid coordinates from the observed system Update button')
    require_visible_confirmation(tuple(map(int, point.split())))
    if installed_version() != 2:
        raise ValueError('Version changed before system confirmation')
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
