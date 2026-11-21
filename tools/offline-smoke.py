"""UI + cold start smoke test, on a dedicated airplane-mode emulator only.
Run with Python 3: python -I tools/offline-smoke.py --adb <adb> --serial emulator-5554
Creates one uniquely named synthetic notebook and removes only that book via the UI.
"""
import argparse
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--adb', default='adb')
    ap.add_argument('--serial', required=True)
    args = ap.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise SystemExit('Use a dedicated emulator, not a personal phone.')
    def adb(*cmd):
        return subprocess.run([args.adb, '-s', args.serial, *cmd], check=True,
                              capture_output=True, timeout=25).stdout.decode('utf-8', errors='replace')
    if adb('shell', 'settings', 'get', 'global', 'airplane_mode_on').strip() != '1':
        raise SystemExit('Enable airplane mode on the test emulator first.')
    app = 'app.paperharbor.notes.debug'
    name = 'PH' + uuid.uuid4().hex[:10]
    word = 'Harbor' + uuid.uuid4().hex[:10]
    def start():
        adb('shell', 'am', 'start', '-W', '-n', app + '/app.paperharbor.notes.MainActivity')
    def node(**fields):
        end = time.monotonic() + 35
        while time.monotonic() < end:
            raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
            a, b = raw.find('<?xml'), raw.rfind('</hierarchy>')
            if a >= 0 and b > a:
                for element in ET.fromstring(raw[a:b+12]).iter('node'):
                    if all(element.get(k) == v for k, v in fields.items()):
                        xy = list(map(int, re.findall(r'-?\d+', element.get('bounds', ''))))
                        if len(xy) == 4: return xy
            time.sleep(.25)
        raise AssertionError('Element unavailable: ' + repr(fields))
    def tap(**fields):
        x, y, r, b = node(**fields)
        adb('shell', 'input', 'tap', str((x+r)//2), str((y+b)//2))
    def by_text(text): tap(text=text)
    def type_into_edit(value):
        tap(**{'class': 'android.widget.EditText'})
        adb('shell', 'input', 'text', value)
    def editor_ready(): node(**{'content-desc': '笔记画布'})
    start(); by_text('＋ 新建笔记'); type_into_edit(name); by_text('创建'); editor_ready()
    left, top, right, bottom = node(**{'content-desc': '笔记画布'})
    x, y = (left+right)//2, (top+bottom)//2
    adb('shell', 'input', 'swipe', str(x-30), str(y-10), str(x+30), str(y+35), '260')
    node(text='已存本机'); by_text('更多'); by_text('本页文字'); type_into_edit(word); by_text('保存'); node(text='已存本机')
    by_text('＋ 页'); by_text('方格纸'); node(**{'content-desc': '页面列表，第 2 页'}); by_text('书架'); node(**{'content-desc': name+'，打开笔记'})
    adb('shell', 'am', 'force-stop', app); start(); tap(**{'class': 'android.widget.EditText'}); adb('shell', 'input', 'text', word)
    node(text='2 页    ·    匹配第 1 页'); tap(**{'content-desc': name+'，打开笔记'}); editor_ready()
    by_text('更多'); by_text('本页文字'); node(text=word); by_text('取消'); by_text('更多'); by_text('删除这本笔记'); by_text('删除'); node(text='没有找到匹配的本地笔记')
    print('PASS: airplane-mode create, stroke, saved page text, second page, process restart, content search, restored text and targeted cleanup.')


if __name__ == '__main__': main()
