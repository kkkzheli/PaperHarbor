"""Tiny UI driver for the local device used by the acceptance runs.

Usage: python tools/phone.py <serial> shot <name> | tap <content-desc> | tap-text <text> |
       swipe x1 y1 x2 y2 ms | dump
Prints ASCII only so it is safe in any console; screenshots land in the project root.
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r'C:\Users\Lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe'


def run(serial, *args):
    return subprocess.run([ADB, '-s', serial, *args], capture_output=True)


def nodes(serial):
    run(serial, 'shell', 'uiautomator', 'dump', '/sdcard/ph.xml')
    raw = subprocess.run([ADB, '-s', serial, 'exec-out', 'cat', '/sdcard/ph.xml'],
                         capture_output=True).stdout
    return list(ET.fromstring(raw.decode('utf-8')).iter('node'))


def center(node):
    x1, y1, x2, y2 = [int(v) for v in node.get('bounds').replace('][', ',').strip('[]').split(',')]
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(serial, desc=None, text=None):
    for n in nodes(serial):
        if desc is not None and (n.get('content-desc') or '') == desc:
            return n
        if text is not None and (n.get('text') or '') == text:
            return n
    return None


def main():
    serial = sys.argv[1]
    cmd = sys.argv[2]
    if cmd == 'shot':
        out = open(sys.argv[3] + '.png', 'wb')
        out.write(subprocess.run([ADB, '-s', serial, 'exec-out', 'screencap', '-p'],
                                 capture_output=True).stdout)
        out.close()
        print('shot', sys.argv[3])
    elif cmd == 'tap':
        node = find(serial, desc=sys.argv[3])
        if node is None:
            print('NOT FOUND desc')
            return 1
        x, y = center(node)
        run(serial, 'shell', 'input', 'tap', str(x), str(y))
        print('tap', x, y)
    elif cmd == 'tap-text':
        node = find(serial, text=sys.argv[3])
        if node is None:
            print('NOT FOUND text')
            return 1
        x, y = center(node)
        run(serial, 'shell', 'input', 'tap', str(x), str(y))
        print('tap', x, y)
    elif cmd == 'swipe':
        run(serial, 'shell', 'input', 'swipe', *sys.argv[3:7])
        print('swipe')
    elif cmd == 'dump':
        for n in nodes(serial):
            desc, text = n.get('content-desc'), n.get('text')
            if desc or text:
                print(repr(desc), repr(text), n.get('bounds'))
    else:
        print('unknown command')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main() or 0)
