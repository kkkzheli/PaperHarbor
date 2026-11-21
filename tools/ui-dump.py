"""Read the current Android window tree and report layout problems.
Usage: python -I tools/ui-dump.py --adb <adb> --serial <serial> [--expect-canvas]
Reads via a temporary device file, so non-ASCII text is never corrupted by the shell.
"""
import argparse
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default='adb')
    p.add_argument('--serial', required=True)
    p.add_argument('--expect-canvas', action='store_true')
    a = p.parse_args()
    def run(*cmd):
        return subprocess.run([a.adb, '-s', a.serial, *cmd], check=True, capture_output=True, timeout=40).stdout
    run('shell', 'uiautomator', 'dump', '/sdcard/ph-window.xml')
    with tempfile.TemporaryDirectory() as tmp:
        run('pull', '/sdcard/ph-window.xml', str(Path(tmp) / 'w.xml'))
        raw = (Path(tmp) / 'w.xml').read_bytes()
    root = ET.fromstring(raw)
    nodes = list(root.iter('node'))
    size = run('shell', 'wm', 'size').decode()
    import re
    m = re.search(r'Physical size: (\d+)x(\d+)', size)
    sw, sh = int(m.group(1)), int(m.group(2))
    rotation = run('shell', 'dumpsys', 'window', 'displays').decode('utf-8', 'replace')
    if re.search(r'mCurrentRotation=ROTATION_(90|270)', rotation):
        sw, sh = sh, sw
    print(f'screen {sw}x{sh}')
    bad = []
    for n in nodes:
        b = n.get('bounds', '')
        if not b:
            continue
        x1, y1, x2, y2 = [int(v) for v in b.replace('][', ',').strip('[]').split(',')]
        if x1 < 0 or y1 < 0 or x2 > sw or y2 > sh:
            bad.append((n.get('class'), n.get('text') or n.get('content-desc'), b))
    canvases = [n for n in nodes if n.get('content-desc') == '笔记画布']
    if canvases:
        x1, y1, x2, y2 = [int(v) for v in canvases[0].get('bounds').replace('][', ',').strip('[]').split(',')]
        print(f'canvas bounds: {x2-x1}x{y2-y1} px in {sw}x{sh} px screen')
    print(f'{len(nodes)} nodes; {len(bad)} outside screen bounds')
    for item in bad[:12]:
        print('  OUTSIDE', item)
    if a.expect_canvas:
        if not canvases:
            sys.exit('FAIL: note canvas is missing')
        print('canvas present')
    if bad:
        sys.exit('FAIL: elements overflow the screen')


if __name__ == '__main__':
    main()
