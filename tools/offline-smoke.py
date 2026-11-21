"""UI + cold start smoke test for the icon-first PaperHarbor UI.
Usage: python -I tools/offline-smoke.py --adb <adb> --serial <serial> [--allow-device]
Creates one uniquely named synthetic notebook and deletes only that notebook.
Uses accessibility descriptions, so it does not depend on button wording.
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
    if re.fullmatch(r'emulator-\d+', args.serial):
        if shell('settings', 'get', 'global', 'airplane_mode_on').strip() != '1':
            raise SystemExit('Enable airplane mode on the test emulator first.')
    else:
        if 'android.permission.INTERNET' in shell('dumpsys', 'package', 'app.paperharbor.notes.debug'):
            raise SystemExit('Installed app requests INTERNET; stop before testing.')
        print('Physical device: installed package requests no INTERNET permission.')
    app = 'app.paperharbor.notes.debug'
    name = 'PH' + uuid.uuid4().hex[:10]
    word = 'Harbor' + uuid.uuid4().hex[:10]

    def nodes():
        adb('shell', 'uiautomator', 'dump', '/sdcard/ph-ui.xml')
        with tempfile.TemporaryDirectory() as tmp:
            adb('pull', '/sdcard/ph-ui.xml', str(Path(tmp) / 'w.xml'))
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

    def type_into_all(value):
        tap(wait(**{'class': 'android.widget.EditText'}))
        shell('input', 'text', value)

    def clear_search():
        shell('input', 'keyevent', '123')
        for _ in range(60):
            shell('input', 'keyevent', '67')

    def start():
        shell('am', 'start', '-W', '-n', app + '/app.paperharbor.notes.MainActivity')

    def create_note():
        tap(wait(**{'content-desc': '新建笔记'}))
        type_into_all(name)
        hide_keyboard()
        tap(wait(text='创建'))
        wait(**{'content-desc': '笔记画布'})

    start()
    create_note()
    canvas = wait(**{'content-desc': '笔记画布'})
    x1, y1, x2, y2 = [int(v) for v in canvas.get('bounds').replace('][', ',').strip('[]').split(',')]
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    shell('input', 'swipe', str(x - 40), str(y - 20), str(x + 40), str(y + 45), '260')
    wait(text='已存本机')

    tap(wait(**{'content-desc': '更多工具'}))
    tap(wait(text='页内文字'))
    type_into_all(word)
    hide_keyboard()
    tap(wait(text='保存'))
    wait(text='已存本机')

    tap(wait_desc_prefix('页面：'))
    tap(wait(**{'content-desc': '添加页面'}))
    tap(wait(**{'content-desc': '方格纸'}))
    wait(**{'content-desc': '页面：第 2 页，共 2 页'})

    tap(wait(**{'content-desc': '返回书架'}))
    wait(**{'content-desc': '打开笔记：' + name})

    shell('am', 'force-stop', app)
    start()
    tap(wait(**{'class': 'android.widget.EditText'}))
    clear_search()
    shell('input', 'text', word)
    hide_keyboard()
    tap(wait(**{'content-desc': '打开笔记：' + name}))
    wait(**{'content-desc': '笔记画布'})
    tap(wait(**{'content-desc': '更多工具'}))
    tap(wait(text='页内文字'))
    wait(text=word)
    shell('input', 'keyevent', '4')  # close the text sheet without saving
    time.sleep(.8)
    tap(wait(**{'content-desc': '更多工具'}))
    tap(wait(text='删除'))
    time.sleep(.9)
    tap(wait(text='删除'))
    wait(**{'content-desc': '新建笔记'})
    assert find(**{'content-desc': '打开笔记：' + name}) is None, 'synthetic note still on shelf'
    print('PASS: icon UI create, ink save, page text, added page, restart, search, delete')


if __name__ == '__main__':
    main()
