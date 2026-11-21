"""Create one synthetic note and check editor layout in landscape and 360 dp width.
Usage: python -I tools/layout-check.py --adb <adb> --serial <device> --allow-device
Deletes only the uniquely named synthetic note it created; resets display overrides it set.
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
    args = ap.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) and not args.allow_device:
        raise SystemExit('Use a dedicated emulator or pass --allow-device.')
    def adb(*cmd):
        return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True, capture_output=True, timeout=40).stdout
    def shell(*cmd):
        return adb('shell', *cmd).decode('utf-8', 'replace')
    root = run = None
    def dump():
        adb('shell', 'uiautomator', 'dump', '/sdcard/ph-layout.xml')
        with tempfile.TemporaryDirectory() as tmp:
            adb('pull', '/sdcard/ph-layout.xml', str(Path(tmp) / 'w.xml'))
            raw = (Path(tmp) / 'w.xml').read_bytes()
        return list(ET.fromstring(raw).iter('node'))
    def screen():
        m = re.search(r'Physical size: (\d+)x(\d+)', shell('wm', 'size'))
        w, h = int(m.group(1)), int(m.group(2))
        if re.search(r'mCurrentRotation=ROTATION_(90|270)', shell('dumpsys', 'window', 'displays')):
            w, h = h, w
        return w, h
    def wait(**attrs):
        end = time.monotonic() + 35
        while time.monotonic() < end:
            for n in dump():
                if all(n.get(k) == v for k, v in attrs.items()):
                    return n
            time.sleep(.3)
        raise AssertionError('Missing element: ' + repr(attrs))
    def exists(**attrs):
        try:
            wait(**attrs); return True
        except AssertionError:
            return False
    def hide_keyboard():
        # Landscape IMEs may switch to a full-screen extract view where mInputShown is false
        # while mIsInputViewShown stays true; either state must be dismissed.
        state = shell('dumpsys', 'input_method')
        if 'mInputShown=true' in state or 'mIsInputViewShown=true' in state:
            shell('input', 'keyevent', '4'); time.sleep(.8)
    def tap_button(label):
        hide_keyboard()
        for _ in range(3):
            tap(wait(text=label)); time.sleep(.8)
            try:
                wait(**{'content-desc': '笔记画布'}); return
            except AssertionError:
                pass
        wait(**{'content-desc': '笔记画布'})
    def tap(node):
        x1, y1, x2, y2 = [int(v) for v in node.get('bounds').replace('][', ',').strip('[]').split(',')]
        shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    def type_text(value):
        shell('input', 'text', value)
    def clear_field():
        # The shelf keeps the user's previous filter text, and tapping centers the cursor.
        shell('input', 'keyevent', '123')
        for _ in range(80):
            shell('input', 'keyevent', '67')
    def overflow(nodes, w, h):
        bad = []
        for n in nodes:
            b = n.get('bounds', '')
            if not b:
                continue
            x1, y1, x2, y2 = [int(v) for v in b.replace('][', ',').strip('[]').split(',')]
            if x1 < 0 or y1 < 0 or x2 > w or y2 > h:
                bad.append((n.get('class'), n.get('text') or n.get('content-desc'), b))
        return bad
    def report(label):
        nodes = dump()
        w, h = screen()
        canvases = [n for n in nodes if n.get('content-desc') == '笔记画布']
        if not canvases:
            raise AssertionError(label + ': editor canvas missing')
        x1, y1, x2, y2 = [int(v) for v in canvases[0].get('bounds').replace('][', ',').strip('[]').split(',')]
        bad = overflow(nodes, w, h)
        print(f'{label}: screen {w}x{h}; canvas {x2-x1}x{y2-y1}; {len(nodes)} nodes; overflow {len(bad)}')
        if bad:
            for item in bad[:8]:
                print('  ', item)
            raise AssertionError(label + ': layout overflow')
        return canvases[0]
    name, token = 'PHLayout' + uuid.uuid4().hex[:8], 'rotate' + uuid.uuid4().hex[:8]
    original_rotation = shell('settings', 'get', 'system', 'user_rotation').strip()
    original_accel = shell('settings', 'get', 'system', 'accelerometer_rotation').strip()
    original_size = shell('wm', 'size').strip()
    try:
        shell('am', 'start', '-W', '-n', 'app.paperharbor.notes.debug/app.paperharbor.notes.MainActivity')
        if exists(text='创建') and exists(text='取消'):
            tap(wait(text='取消')); time.sleep(.5)
        if any(n.get('content-desc') == '笔记画布' for n in dump()):
            tap(wait(text='书架'))
        tap(wait(text='＋ 新建笔记'))
        edit = wait(**{'class': 'android.widget.EditText'})
        tap(edit); type_text(name); tap_button('创建')
        shell('settings', 'put', 'system', 'accelerometer_rotation', '0')
        shell('settings', 'put', 'system', 'user_rotation', '0')
        time.sleep(1.5); report('portrait')
        shell('settings', 'put', 'system', 'user_rotation', '1')
        time.sleep(2); canvas = report('landscape')
        x1, y1, x2, y2 = [int(v) for v in canvas.get('bounds').replace('][', ',').strip('[]').split(',')]
        x, y = (x1+x2)//2, (y1+y2)//2
        shell('input', 'swipe', str(x-40), str(y-15), str(x+40), str(y+30), '250')
        wait(text='已存本机')
        shell('settings', 'put', 'system', 'user_rotation', '0')
        time.sleep(1.5); report('back to portrait')
        m = re.search(r'Physical size: (\d+)x(\d+)', original_size)
        shell('wm', 'size', '1080x2400'); shell('wm', 'density', '480')
        time.sleep(2)
        tap(wait(text='书架'))  # leave editor so the shelf layout is exercised too
        nodes = dump(); w, h = screen(); bad = overflow(nodes, w, h)
        print(f'360dp shelf: screen {w}x{h}; {len(nodes)} nodes; overflow {len(bad)}')
        if bad:
            for item in bad[:8]: print('  ', item)
            raise AssertionError('360dp shelf overflow')
        shell('wm', 'size', 'reset')
        time.sleep(1)
        shell('am', 'start', '-W', '-n', 'app.paperharbor.notes.debug/app.paperharbor.notes.MainActivity')
        search = wait(**{'class': 'android.widget.EditText'})
        tap(search); clear_field(); type_text(name); hide_keyboard()
        tap(wait(**{'content-desc': name + '，打开笔记'}))
        wait(**{'content-desc': '笔记画布'})
        tap(wait(text='更多')); tap(wait(text='删除这本笔记')); tap(wait(text='删除'))
        print('PASS: portrait, landscape drawing, back to portrait, 360dp shelf, targeted cleanup')
    finally:
        shell('wm', 'size', 'reset'); shell('wm', 'density', 'reset')
        shell('settings', 'put', 'system', 'accelerometer_rotation', original_accel)
        shell('settings', 'put', 'system', 'user_rotation', original_rotation)


if __name__ == '__main__':
    main()
