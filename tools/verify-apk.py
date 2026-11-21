"""Check the *built APK*, not just source XML. SDK tools must be installed locally."""
import argparse
import hashlib
import os
import re
import subprocess
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk', type=Path)
    p.add_argument('--build-tools', required=True, type=Path)
    p.add_argument('--unsigned', action='store_true', help='Expect uninstalled release variant; do not verify its signature')
    args = p.parse_args()
    def tool(name, *flags):
        path = args.build_tools / (name + ('.bat' if name == 'apksigner' else '.exe') if os.name == 'nt' else name)
        return subprocess.check_output([str(path), *flags, str(args.apk)], stderr=subprocess.STDOUT).decode('utf-8', errors='replace')
    manifest = tool('aapt', 'dump', 'badging')
    names = re.findall(r"^uses-permission(?:-sdk-\d+)?: name='([^']+)'", manifest, re.M)
    if set(names) != {'android.permission.RECORD_AUDIO'}:
        raise SystemExit(f'Unexpected permission set: {names}')
    declared = re.search(r"^package: name='([^']+)'", manifest, re.M).group(1)
    if declared not in ('app.paperharbor.notes', 'app.paperharbor.notes.debug'):
        raise SystemExit('Unexpected package: ' + declared)
    if not args.unsigned: tool('apksigner', 'verify', '--verbose')
    print(f'{declared}: microphone only; no INTERNET or network-state permission; ' + ('unsigned release check' if args.unsigned else 'signature verified'))
    print(hashlib.sha256(args.apk.read_bytes()).hexdigest() + '  ' + args.apk.name)


if __name__ == '__main__': main()
