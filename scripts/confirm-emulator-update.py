#!/usr/bin/env python3
"""Accept only FORMA's real system update dialog on the disposable emulator."""
import re
import subprocess
import time
from xml.etree import ElementTree


PACKAGE = 'ru.forma.fitness'
INSTALLERS = {'com.android.packageinstaller', 'com.google.android.packageinstaller'}
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
    for installer in INSTALLERS:
        controls = [node.attrib for node in nodes if node.get('package') == installer]
        text = {control.get('text', '').strip().casefold() for control in controls}
        if not {'forma', 'cancel'} <= text:
            continue
        buttons = [control for control in controls
                   if control.get('resource-id') == 'android:id/button1'
                   and control.get('text', '').strip().casefold() in {'update', 'install'}
                   and control.get('enabled') == 'true' and control.get('clickable') == 'true']
        if len(buttons) != 1:
            continue
        match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', buttons[0].get('bounds', ''))
        if match:
            left, top, right, bottom = map(int, match.groups())
            if left < right and top < bottom:
                return (left + right) // 2, (top + bottom) // 2
    return None


def wait_for_confirmation():
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        adb('shell', 'rm', '-f', DUMP)
        try:
            adb('shell', 'uiautomator', 'dump', '--compressed', DUMP)
            point = confirmation_center(adb('exec-out', 'cat', DUMP))
        except (subprocess.CalledProcessError, ElementTree.ParseError):
            time.sleep(1)
            continue
        if point is not None:
            return point
    raise ValueError('FORMA update confirmation was not visible in the Android package installer')


def main():
    if (not adb('get-serialno').strip().startswith('emulator-')
            or adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '34'):
        raise ValueError('Expected the disposable API 34 emulator')
    try:
        point = wait_for_confirmation()
        if installed_version() != 2:
            raise ValueError('Version changed before system confirmation')
        print('PASS Android confirmation visible while versionCode is still 2', flush=True)
        adb('shell', 'input', 'tap', *(str(coordinate) for coordinate in point))
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if installed_version() == 3:
                print('PASS system confirmation installed versionCode 3', flush=True)
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                return
            time.sleep(1)
        raise ValueError('Android did not install versionCode 3 after confirming')
    finally:
        subprocess.run(['adb', 'shell', 'rm', '-f', DUMP], check=False, timeout=10)


if __name__ == '__main__':
    main()
