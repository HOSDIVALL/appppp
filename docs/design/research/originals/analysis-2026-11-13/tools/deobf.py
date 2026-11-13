"""用官方 AAR 对混淆后的 Compose API 调用做签名级反混淆。

原理：R8 会改写方法名，也会削掉"恒为常量的尾部参数"（Composable 的 `changed: Int`、
`$default` 的尾标记 `Object`），但不会改参数顺序与类型。
因此以 (类, 返回类型) 建索引，用"参数列表前缀"匹配，并要求名字唯一命中。

匹配等级：
  exact          参数列表完全相同
  app_shorter    APK 侧少了尾部参数（R8 削参）
  aar_shorter    APK 侧多了尾部参数
  ambiguous      多个同名不同参的方法都能匹配，无法唯一确定（单列，不作结论）

用法：
    python tools/deobf.py
产出：
    data/api-resolved.csv
    data/animation-api.csv
    data/deobf-summary.json
"""
import io
import os
import sys
import csv
import json
import glob
import struct
import zipfile
import collections

CP_SIZE = {1: None, 3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
           12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def split_desc(desc):
    """'(AB)Lx;' -> (['A','B'], 'Lx;')"""
    assert desc.startswith('(')
    i = 1
    params = []
    while desc[i] != ')':
        j = i
        while desc[j] == '[':
            j += 1
        if desc[j] == 'L':
            j = desc.index(';', j) + 1
        else:
            j += 1
        params.append(desc[i:j])
        i = j
    return tuple(params), desc[i + 1:]


def parse_class(data):
    """返回 (internal_name, [(method_name, descriptor)])"""
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None
    p = 8
    cp_count = struct.unpack_from('>H', data, p)[0]
    p += 2
    utf8 = {}
    cls_ref = {}
    i = 1
    while i < cp_count:
        tag = data[p]
        p += 1
        if tag == 1:
            ln = struct.unpack_from('>H', data, p)[0]
            p += 2
            utf8[i] = data[p:p + ln].decode('utf-8', 'replace')
            p += ln
        elif tag in (7, 8, 16, 19, 20):
            idx = struct.unpack_from('>H', data, p)[0]
            p += 2
            if tag == 7:
                cls_ref[i] = idx
        elif tag == 15:
            p += 3
        elif tag in (5, 6):
            p += 8
            i += 1
        else:
            p += CP_SIZE[tag]
        i += 1
    p += 2
    this_class = struct.unpack_from('>H', data, p)[0]
    p += 4                                    # this_class + super_class
    ic = struct.unpack_from('>H', data, p)[0]
    p += 2 + ic * 2

    def skip_attrs(p, count):
        for _ in range(count):
            ln = struct.unpack_from('>I', data, p + 2)[0]
            p += 6 + ln
        return p

    fc = struct.unpack_from('>H', data, p)[0]
    p += 2
    for _ in range(fc):
        p += 6
        ac = struct.unpack_from('>H', data, p)[0]
        p += 2
        p = skip_attrs(p, ac)
    mc = struct.unpack_from('>H', data, p)[0]
    p += 2
    methods = []
    for _ in range(mc):
        _acc, ni, di = struct.unpack_from('>HHH', data, p)
        p += 6
        methods.append((utf8.get(ni, ''), utf8.get(di, '')))
        ac = struct.unpack_from('>H', data, p)[0]
        p += 2
        p = skip_attrs(p, ac)
    return utf8.get(cls_ref.get(this_class, 0), ''), methods


def load_aars(paths):
    """(类, 返回类型) -> [(参数元组, 方法名)]"""
    table = collections.defaultdict(list)
    stats = []
    for aar in paths:
        z = zipfile.ZipFile(aar)
        for jn in [n for n in z.namelist() if n.endswith('classes.jar')]:
            jz = zipfile.ZipFile(io.BytesIO(z.read(jn)))
            cnt_cls = cnt_m = 0
            for n in jz.namelist():
                if not n.endswith('.class'):
                    continue
                r = parse_class(jz.read(n))
                if not r:
                    continue
                cname, methods = r
                cnt_cls += 1
                key_c = 'L' + cname + ';'
                for mn, md in methods:
                    if not md.startswith('('):
                        continue
                    try:
                        ps, ret = split_desc(md)
                    except Exception:
                        continue
                    table[(key_c, ret)].append((ps, mn))
                    cnt_m += 1
            stats.append({'aar': os.path.basename(aar), 'classes': cnt_cls, 'methods': cnt_m})
    return table, stats


def match(table, tcls, desc):
    try:
        ps, ret = split_desc(desc)
    except Exception:
        return None, None
    cands = table.get((tcls, ret))
    if not cands:
        return None, None
    exact, app_short, aar_short = [], [], []
    for aps, nm in cands:
        if aps == ps:
            exact.append(nm)
        elif len(aps) > len(ps) and aps[:len(ps)] == ps:
            app_short.append(nm)
        elif len(ps) > len(aps) and ps[:len(aps)] == aps:
            aar_short.append(nm)
    for names, kind in ((exact, 'exact'), (app_short, 'app_shorter'), (aar_short, 'aar_shorter')):
        uniq = sorted(set(names))
        if len(uniq) == 1:
            return uniq[0], kind
        if len(uniq) > 1:
            return '|'.join(uniq), 'ambiguous'
    return None, None


def main():
    aars = sorted(glob.glob('libs/*.aar'))
    if not aars:
        print('libs/*.aar 不存在')
        return 1
    table, stats = load_aars(aars)

    rows = list(csv.DictReader(io.open('data/edges-lib.tsv', encoding='utf-8'), delimiter='\t'))
    agg = collections.Counter()
    kinds = collections.Counter()
    unresolved = collections.Counter()
    for r in rows:
        nm, kind = match(table, r['target_class'], r['proto'])
        c = int(r['count'])
        if not nm:
            unresolved[(r['target_class'], r['proto'], r['obf_name'])] += c
            continue
        kinds[kind] += 1
        agg[(r['target_class'], nm, r['proto'], r['obf_name'], kind)] += c

    with io.open('data/api-resolved.csv', 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['target_class', 'real_name', 'match', 'proto', 'obf_name', 'call_sites'])
        for (tc, nm, p, ob, k), c in sorted(agg.items(), key=lambda kv: -kv[1]):
            w.writerow([tc[1:].rstrip(';').replace('/', '.'), nm, k, p, ob, c])

    anim_pfx = ('androidx.compose.animation.core.', 'androidx.compose.animation.',
                'androidx.dynamicanimation.')
    anim = collections.defaultdict(lambda: collections.Counter())
    for (tc, nm, p, ob, k), c in agg.items():
        cls = tc[1:].rstrip(';').replace('/', '.')
        if cls.startswith(anim_pfx):
            anim[cls][nm] += c
    with io.open('data/animation-api.csv', 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['animation_class', 'member', 'call_sites'])
        for cls in sorted(anim):
            for nm, c in anim[cls].most_common():
                w.writerow([cls, nm, c])

    total_sites = sum(c for r in rows for c in [int(r['count'])])
    summary = {
        'aar_sources': stats,
        'edges_in': len(rows),
        'sites_in': total_sites,
        'resolved_distinct': len(agg),
        'resolved_sites': sum(agg.values()),
        'match_kinds': dict(kinds),
        'ambiguous_distinct': sum(1 for k in agg if k[4] == 'ambiguous'),
        'unresolved_edges': len(unresolved),
        'unresolved_sites': sum(unresolved.values()),
        'animation_members': sum(len(v) for v in anim.values()),
    }
    json.dump(summary, io.open('data/deobf-summary.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    if unresolved:
        print('\n未解析：')
        for k, c in list(unresolved.items())[:8]:
            print(' ', k[0][1:].rstrip(';'), k[2], c)
    return 0


if __name__ == '__main__':
    sys.exit(main())
