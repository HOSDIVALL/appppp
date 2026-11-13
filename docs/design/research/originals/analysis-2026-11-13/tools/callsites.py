"""DEX 指令级调用点解析。

与 gen_data.py 的区别：gen_data.py 只统计 method_ids 词表里"出现过"哪些 API，
无法回答"谁调用了它"。本脚本解码每个方法的 code_item，逐条反汇编 invoke-* 指令，
得到真实的 (调用方类 -> 被调方 类#方法) 调用边。

用法：
    python tools/callsites.py <apk>            # 产出 data/callsite-*.csv / .json
需要：仅标准库。
"""
import io
import os
import sys
import json
import struct
import zipfile
import collections
import csv

# ---------- Dalvik 指令长度表（单位：16 bit） ----------

def _build_sizes():
    s = [1] * 256
    two = [0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1A, 0x1C, 0x1F, 0x20,
           0x22, 0x23, 0x29, 0xFE, 0xFF]
    two += list(range(0x2D, 0x3E))          # cmp* / if-* / if-*z
    two += list(range(0x44, 0x6E))          # aget/aput / iget/iput / sget/sput
    two += list(range(0x90, 0xB0))          # binop
    two += list(range(0xD0, 0xE3))          # binop/lit16, binop/lit8
    for op in two:
        s[op] = 2
    three = [0x03, 0x06, 0x09, 0x14, 0x17, 0x1B, 0x24, 0x25, 0x26,
             0x2A, 0x2B, 0x2C, 0xFC, 0xFD]
    for op in three:
        s[op] = 3
    for op in (0x6E, 0x6F, 0x70, 0x71, 0x72, 0x74, 0x75, 0x76, 0x77, 0x78):
        s[op] = 3
    for op in (0xFA, 0xFB):
        s[op] = 4
    s[0x18] = 5                              # const-wide
    s[0x00] = 1                              # nop / payload，循环内特判
    return s


SIZE = _build_sizes()
INVOKES = set(range(0x6E, 0x73)) | set(range(0x74, 0x79)) | {0xFA, 0xFB}


def uleb(d, off):
    r = 0
    s = 0
    while True:
        b = d[off]
        off += 1
        r |= (b & 0x7F) << s
        if not (b & 0x80):
            break
        s += 7
    return r, off


class Dex:
    def __init__(self, path):
        self.d = open(path, 'rb').read()
        d = self.d
        if d[:4] != b'dex\n':
            raise ValueError('not a dex: ' + path)
        self.str_n, self.str_off = struct.unpack_from('<II', d, 0x38)
        self.type_n, self.type_off = struct.unpack_from('<II', d, 0x40)
        self.proto_n, self.proto_off = struct.unpack_from('<II', d, 0x48)
        self.field_n, self.field_off = struct.unpack_from('<II', d, 0x50)
        self.meth_n, self.meth_off = struct.unpack_from('<II', d, 0x58)
        self.cls_n, self.cls_off = struct.unpack_from('<II', d, 0x60)
        self._strings = {}
        self._types = {}
        self._meths = None
        self._protos = None

    # ---- 延迟解码，省内存 ----
    def string(self, i):
        v = self._strings.get(i)
        if v is None:
            off = struct.unpack_from('<I', self.d, self.str_off + i * 4)[0]
            j = off
            while self.d[j] & 0x80:
                j += 1
            j += 1
            end = self.d.index(b'\x00', j)
            v = self.d[j:end].decode('utf-8', 'replace')
            self._strings[i] = v
        return v

    def type(self, i):
        v = self._types.get(i)
        if v is None:
            v = self.string(struct.unpack_from('<I', self.d, self.type_off + i * 4)[0])
            self._types[i] = v
        return v

    def method(self, i):
        if self._meths is None:
            self._meths = []
            for k in range(self.meth_n):
                c, p, n = struct.unpack_from('<HHI', self.d, self.meth_off + k * 8)
                self._meths.append((self.type(c), self.string(n), p))
        return self._meths[i]

    def proto(self, i):
        """proto_id_item -> JVM 风格描述符，如 (Lkotlin/jvm/functions/Function0;)V"""
        if self._protos is None:
            self._protos = []
            for k in range(self.proto_n):
                _shorty, ret, params_off = struct.unpack_from(
                    '<III', self.d, self.proto_off + k * 12)
                ps = []
                if params_off:
                    cnt = struct.unpack_from('<I', self.d, params_off)[0]
                    for j in range(cnt):
                        ti = struct.unpack_from('<H', self.d, params_off + 4 + j * 2)[0]
                        ps.append(self.type(ti))
                self._protos.append('(' + ''.join(ps) + ')' + self.type(ret))
        return self._protos[i]

    def class_defs(self):
        for i in range(self.cls_n):
            base = self.cls_off + i * 32
            cls_idx, _acc, _sup, _ifs, _src, _ann, cdata, _sv = struct.unpack_from(
                '<IIIIIIII', self.d, base)
            yield self.type(cls_idx), cdata

    def methods_of(self, cdata):
        """返回 [(method_idx, code_off)]，含 direct 与 virtual。"""
        if not cdata:
            return []
        off = cdata
        sf, off = uleb(self.d, off)
        inf, off = uleb(self.d, off)
        dm, off = uleb(self.d, off)
        vm, off = uleb(self.d, off)
        for _ in range(sf + inf):
            _, off = uleb(self.d, off)
            _, off = uleb(self.d, off)
        out = []
        for total in (dm, vm):
            cur = 0
            for _ in range(total):
                diff, off = uleb(self.d, off)
                _flags, off = uleb(self.d, off)
                code, off = uleb(self.d, off)
                cur += diff
                if code:
                    out.append((cur, code))
        return out

    def invokes_in(self, code_off):
        """解码单个 code_item，产出被 invoke 的 method_idx。"""
        d = self.d
        if code_off + 16 > len(d):
            return
        insns_size = struct.unpack_from('<I', d, code_off + 12)[0]
        base = code_off + 16
        if insns_size == 0 or base + insns_size * 2 > len(d):
            return
        ins = struct.unpack_from('<%dH' % insns_size, d, base)
        n = insns_size
        i = 0
        while i < n:
            u = ins[i]
            op = u & 0xFF
            if op in INVOKES:
                if i + 1 < n:
                    yield ins[i + 1]
                i += SIZE[op]
                continue
            if op == 0x00:
                hi = u >> 8
                if hi == 0x01:                                  # packed-switch payload
                    size = ins[i + 1] if i + 1 < n else 0
                    i += 4 + size * 2
                elif hi == 0x02:                                # sparse-switch payload
                    size = ins[i + 1] if i + 1 < n else 0
                    i += 2 + size * 4
                elif hi == 0x03:                                # fill-array-data payload
                    width = ins[i + 1] if i + 1 < n else 0
                    count = ins[i + 2] | (ins[i + 3] << 16) if i + 3 < n else 0
                    i += 4 + (width * count + 1) // 2
                else:
                    i += 1
                continue
            i += SIZE[op]


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    if not apk:
        print(__doc__)
        return 1
    tmp = 'dex'
    os.makedirs(tmp, exist_ok=True)
    z = zipfile.ZipFile(apk)
    names = [n for n in z.namelist() if n.endswith('.dex')]
    for n in names:
        open(os.path.join(tmp, n), 'wb').write(z.read(n))

    own_pfx = 'Lchromahub/rhythm/app/'
    groups = {
        'm3': 'Landroidx/compose/material3/',
        'anim': 'Landroidx/compose/animation/',
        'anim_core': 'Landroidx/compose/animation/core/',
        'foundation': 'Landroidx/compose/foundation/',
    }

    # class -> Counter( (target_class, method_name) )
    edges = collections.defaultdict(collections.Counter)
    # (caller, target_class, proto_desc, obf_name) -> count，供反混淆用
    lib_edges = collections.Counter()
    lib_pfx = ('Landroidx/compose/material3/',
               'Landroidx/compose/animation/core/',
               'Landroidx/compose/animation/',
               'Landroidx/dynamicanimation/')
    total_invokes = 0
    decoded_methods = 0
    decoded_classes = 0

    for n in names:
        dx = Dex(os.path.join(tmp, n))
        for cname, cdata in dx.class_defs():
            if not cname.startswith(own_pfx):
                continue
            decoded_classes += 1
            for midx, code in dx.methods_of(cdata):
                decoded_methods += 1
                for t in dx.invokes_in(code):
                    total_invokes += 1
                    tcls, tname, pidx = dx.method(t)
                    edges[cname][(tcls, tname)] += 1
                    if tcls.startswith(lib_pfx):
                        lib_edges[(cname, tcls, dx.proto(pidx), tname)] += 1
        del dx

    with io.open('data/edges-lib.tsv', 'w', encoding='utf-8', newline='') as f:
        f.write('caller\ttarget_class\tproto\tobf_name\tcount\n')
        for (caller, tcls, desc, tname), c in sorted(lib_edges.items()):
            f.write('%s\t%s\t%s\t%s\t%d\n' % (caller, tcls, desc, tname, c))

    # 1) material3 组件 -> 调用它的自有类
    m3_by_target = collections.defaultdict(lambda: collections.Counter())
    for cname, cnt in edges.items():
        for (tcls, tname), c in cnt.items():
            if not tcls.startswith(groups['m3']):
                continue
            simple = tcls[1:].rstrip(';').split('/')[-1]
            m3_by_target[(simple, tname)][cname] += c

    with io.open('data/m3-components.csv', 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['m3_class', 'member', 'own_callers', 'call_sites'])
        for (simple, tname), callers in sorted(
                m3_by_target.items(), key=lambda kv: (-len(kv[1]), kv[0][0])):
            w.writerow([simple, tname, len(callers), sum(callers.values())])

    # 2) 自有类 -> 用到的 m3 API
    with io.open('data/m3-usage-by-class.csv', 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['own_class', 'm3_target', 'call_sites'])
        for cname in sorted(edges):
            for (tcls, tname), c in sorted(edges[cname].items()):
                if tcls.startswith(groups['m3']):
                    simple = tcls[1:].rstrip(';').split('/')[-1]
                    w.writerow([cname[1:].rstrip(';'), '%s#%s' % (simple, tname), c])

    # 3) 自有类 -> 自有类（组件组装关系）
    with io.open('data/own-calls.csv', 'w', encoding='utf-8', newline='') as f:
        w = csv.writer(f)
        w.writerow(['caller', 'callee', 'call_sites'])
        for cname in sorted(edges):
            agg = collections.Counter()
            for (tcls, tname), c in edges[cname].items():
                if tcls.startswith(own_pfx) and not tcls.startswith(cname[:28]):
                    agg[tcls] += c
            for tcls, c in sorted(agg.items()):
                w.writerow([cname[1:].rstrip(';'), tcls[1:].rstrip(';'), c])

    # 4) 汇总
    grp_tot = {g: 0 for g in groups}
    for cname, cnt in edges.items():
        for (tcls, tname), c in cnt.items():
            for g, p in groups.items():
                if tcls.startswith(p):
                    grp_tot[g] += c
    summary = {
        'dex_files': names,
        'own_classes_decoded': decoded_classes,
        'own_methods_decoded': decoded_methods,
        'total_invoke_sites': total_invokes,
        'invoke_by_group': grp_tot,
        'distinct_m3_members_called': len(m3_by_target),
        'distinct_m3_classes_called': len({k[0] for k in m3_by_target}),
        'own_classes_using_m3': len({c for callers in m3_by_target.values() for c in callers}),
    }
    json.dump(summary, io.open('data/callsite-summary.json', 'w', encoding='utf-8'),
              ensure_ascii=False, indent=1)
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    sys.exit(main())
