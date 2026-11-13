"""源码侧 Material3 / 动画 API 清点。

按 `import` 行统计最可靠（不受命名前缀、别名影响），再统计每个符号在文件内的
调用次数，产出：
    data/source-m3-imports.csv
    data/source-animation-imports.csv
用法：python tools/source_scan.py <repo-root>
"""
import io
import os
import re
import csv
import sys
import json
import collections

IMPORT_RE = re.compile(r'^\s*import\s+([\w\.\*]+)(?:\s+as\s+(\w+))?\s*$', re.M)


def scan(root, sets=('main', 'fdroid')):
    files = []
    for st in sets:
        base = os.path.join(root, 'app', 'src', st)
        for dirpath, _dirs, names in os.walk(base):
            for n in names:
                if n.endswith('.kt'):
                    files.append(os.path.join(dirpath, n))
    m3 = collections.defaultdict(lambda: {'files': set(), 'calls': 0, 'imports': set()})
    anim = collections.defaultdict(lambda: {'files': set(), 'calls': 0, 'imports': set()})
    for p in files:
        s = io.open(p, encoding='utf-8', errors='replace').read()
        rel = os.path.relpath(p, root).replace('\\', '/')
        # 去掉 import 行，避免把导入本身算成一次使用
        body = '\n'.join(l for l in s.split('\n') if not l.lstrip().startswith('import '))
        for m in IMPORT_RE.finditer(s):
            full, alias = m.group(1), m.group(2)
            if full.startswith('androidx.compose.material3'):
                bucket = m3
            elif full.startswith(('androidx.compose.animation', 'androidx.dynamicanimation')):
                bucket = anim
            else:
                continue
            sym = full.rsplit('.', 1)[-1]
            if sym == '*':
                sym = '(wildcard)'
            use = alias or sym
            rec = bucket[(full, sym)]
            rec['files'].add(rel)
            rec['imports'].add(rel)
            if sym != '(wildcard)':
                # 独立 token 即算一次使用，含全限定写法 a.b.C(...)
                rec['calls'] += len(re.findall(r'(?<![\w])' + re.escape(use) + r'(?![\w])', body))
    return m3, anim, len(files)


def dump(bucket, path, pkg_strip):
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['package', 'symbol', 'files', 'uses'])
        for (full, sym), rec in sorted(bucket.items(), key=lambda kv: -kv[1]['calls']):
            w.writerow([full.rsplit('.', 1)[0].replace(pkg_strip, ''), sym,
                        len(rec['files']), rec['calls']])


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    m3, anim, nf = scan(root)
    dump(m3, 'data/source-m3-imports.csv', 'androidx.compose.material3')
    dump(anim, 'data/source-animation-imports.csv', 'androidx.compose.animation')
    summary = {
        'kt_files_scanned': nf,
        'm3_distinct_symbols': len(m3),
        'm3_files': len({f for r in m3.values() for f in r['files']}),
        'm3_total_uses': sum(r['calls'] for r in m3.values()),
        'anim_distinct_symbols': len(anim),
        'anim_total_uses': sum(r['calls'] for r in anim.values()),
    }
    json.dump(summary, io.open('data/source-summary.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
