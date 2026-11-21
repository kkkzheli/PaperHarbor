"""Create one synthetic note and check the icon-first editor in portrait, landscape and 360 dp.
Usage: python -I tools/layout-check.py --adb <adb> --serial <device> [--allow-device]
Deletes only the uniquely named note it created and resets display overrides.
"""
import argparse
import re
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--adb', default='adb')
    ap.add_argument('--serial', required=True)
    ap.add_argument('--allow-device', action='store_true')
    ap.add_argument('--package', default='io.github.kkkzheli.paperharbor')
    args = ap.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) and not args.allow_device:
        raise SystemExit('Use a dedicated emulator or pass --allow-device.')
    def adb(*cmd):
        return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True, timeout=40).stdout
    def shell(*cmd):
        return adb('shell', *cmd).decode('utf-8', 'replace')
    def nodes():
        adb('shell', 'uiautomator', 'dump', '/sdcard/ph-layout.xml')
        with tempfile.TemporaryDirectory() as tmp:
            adb('pull', '/sdcard/ph-layout.xml', str(Path(tmp) / 'w.xml'))
            raw = (Path(tmp) / 'w.xml').read_bytes()
        return list(ET.fromstring(raw).iter('node'))
    def find(**attrs):
        for n in nodes():
            if all(n.get(k) == v for k, v in attrs.items()):
                return n
        return None
    def wait(**attrs):
        end = time.monotonic() + 35
        while time.monotonic() < end:
            hit = find(**attrs)
            if hit is not None:
                return hit
            time.sleep(.3)
        raise AssertionError('Missing element: ' + repr(attrs))
    def wait_desc_prefix(prefix):
        end = time.monotonic() + 35
        while time.monotonic() < end:
            for n in nodes():
                if (n.get('content-desc') or '').startswith(prefix):
                    return n
            time.sleep(.3)
        raise AssertionError('Missing description prefix: ' + prefix)
    def tap(node):
        x1, y1, x2, y2 = [int(v) for v in node.get('bounds').replace('][', ',').strip('[]').split(',')]
        shell('input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    def hide_keyboard():
        state = shell('dumpsys', 'input_method')
        if 'mInputShown=true' in state or 'mIsInputViewShown=true' in state:
            shell('input', 'keyevent', '4')
            time.sleep(.8)
    def clear_field():
        shell('input', 'keyevent', '123')
        for _ in range(80):
            shell('input', 'keyevent', '67')
    def screen():
        size = shell('wm', 'size')
        m = re.search(r'Override size: (\d+)x(\d+)', size) or re.search(r'Physical size: (\d+)x(\d+)', size)
        w, h = int(m.group(1)), int(m.group(2))
        if re.search(r'mCurrentRotation=ROTATION_(90|270)', shell('dumpsys', 'window', 'displays')):
            w, h = h, w
        return w, h
    def report(label):
        tree = nodes()
        w, h = screen()
        canvases = [n for n in tree if n.get('content-desc') == '笔记画布']
        if not canvases:
            raise AssertionError(label + ': editor canvas missing')
        x1, y1, x2, y2 = [int(v) for v in canvases[0].get('bounds').replace('][', ',').strip('[]').split(',')]
        bad = []
        for n in tree:
            b = n.get('bounds', '')
            if not b:
                continue
            a1, b1, a2, b2 = [int(v) for v in b.replace('][', ',').strip('[]').split(',')]
            if a1 < 0 or b1 < 0 or a2 > w or b2 > h:
                bad.append((n.get('class'), n.get('text') or n.get('content-desc'), b))
        print(f'{label}: screen {w}x{h}; canvas {x2-x1}x{y2-y1}; {len(tree)} nodes; overflow {len(bad)}')
        for item in bad[:8]:
            print('  ', item)
        if bad:
            raise AssertionError(label + ': layout overflow')
        return canvases[0]

    name = 'PHLayout' + uuid.uuid4().hex[:8]
    original_rotation = shell('settings', 'get', 'system', 'user_rotation').strip()
    original_accel = shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    try:
        shell('am', 'start', '-W', '-n', args.package + '/io.github.kkkzheli.paperharbor.MainActivity')
        if find(**{'content-desc': '笔记画布'}) is not None:
            tap(wait(**{'content-desc': '返回书架'}))
            wait(**{'content-desc': '新建笔记'})
        tap(wait(**{'content-desc': '新建笔记'}))
        edit = wait(**{'class': 'android.widget.EditText'})
        tap(edit)
        shell('input', 'text', name)
        hide_keyboard()
        tap(wait(text='创建'))
        wait(**{'content-desc': '笔记画布'})

        shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        shell('settings', 'put', 'system', 'user_rotation', '0')
        time.sleep(1.2)
        report('portrait')

        shell('settings', 'put', 'system', 'user_rotation', '1')
        time.sleep(2)
        canvas = report('landscape')
        x1, y1, x2, y2 = [int(v) for v in canvas.get('bounds').replace('][', ',').strip('[]').split(',')]
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        shell('input', 'swipe', str(x - 40), str(y - 15), str(x + 40), str(y + 30), '250')
        wait(text='已存本机')

        shell('settings', 'put', 'system', 'user_rotation', '0')
        time.sleep(1.5)
        report('back to portrait')

        shell('wm', 'size', '1080x2400')
        shell('wm', 'density', '480')
        time.sleep(2)
        tap(wait(**{'content-desc': '返回书架'}))
        wait(**{'content-desc': '新建笔记'})
        tree = nodes()
        w, h = screen()
        bad = []
        for n in tree:
            b = n.get('bounds', '')
            if not b:
                continue
            a1, b1, a2, b2 = [int(v) for v in b.replace('][', ',').strip('[]').split(',')]
            if a1 < 0 or b1 < 0 or a2 > w or b2 > h:
                bad.append((n.get('class'), n.get('text') or n.get('content-desc'), b))
        print(f'360dp shelf: screen {w}x{h}; {len(tree)} nodes; overflow {len(bad)}')
        for item in bad[:8]:
            print('  ', item)
        if bad:
            raise AssertionError('360dp shelf overflow')

        shell('wm', 'size', 'reset')
        shell('wm', 'density', 'reset')
        time.sleep(1.5)
        shell('am', 'start', '-W', '-n', args.package + '/io.github.kkkzheli.paperharbor.MainActivity')
        tap(wait(**{'class': 'android.widget.EditText'}))
        clear_field()
        shell('input', 'text', name)
        hide_keyboard()
        tap(wait(**{'content-desc': '打开笔记：' + name}))
        wait(**{'content-desc': '笔记画布'})
        tap(wait(**{'content-desc': '更多工具'}))
        tap(wait(text='删除'))
        time.sleep(.9)
        tap(wait(text='删除'))
        wait(**{'content-desc': '新建笔记'})
        print('PASS: portrait, landscape drawing, 360dp shelf, targeted cleanup')
    finally:
        shell('wm', 'size', 'reset')
        shell('wm', 'density', 'reset')
        shell('settings', 'put', 'system', 'accelerometer_rotation', original_accel)
        shell('settings', 'put', 'system', 'user_rotation', original_rotation)


if __name__ == '__main__':
    main()
