import glob
import io
import re

BASE = 'app/src/main/java/io/github/kkkzheli/paperharbor/'
src = io.open(BASE + 'Str.java', encoding='utf-8').read()
keys = set(re.findall(r'put\("((?:[^"\\]|\\.)*)"', src))


def unescape(value):
    out = []
    i = 0
    while i < len(value):
        ch = value[i]
        if ch == '\\' and i + 1 < len(value):
            out.append(value[i + 1])
            i += 2
            continue
        out.append(ch)
        i += 1
    return ''.join(out)


def has_chinese(value):
    for ch in value:
        if '一' <= ch <= '鿿':
            return True
    return False


keys = {unescape(k) for k in keys}
missing = {}
for path in glob.glob(BASE + '*.java'):
    if path.endswith('Str.java'):
        continue
    body = io.open(path, encoding='utf-8').read()
    for literal in re.findall(r'"((?:[^"\\]|\\.)*)"', body):
        value = unescape(literal)
        if not has_chinese(value) or value in keys:
            continue
        missing.setdefault(value, set()).add(path.split('/')[-1])

for value, files in sorted(missing.items()):
    print('%-44s %s' % (value, ','.join(sorted(files))))
print('total', len(missing))
