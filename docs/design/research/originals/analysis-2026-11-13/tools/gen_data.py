"""从 Rhythm APK 重建 data/ 下的全部索引文件。

用法：
    python tools/gen_data.py "路径/Rhythm-xxx.apk"

依赖：Python 3.9+，标准库即可。
产出：data/apk-entries.tsv、classes.csv、functions-by-file.csv、api-usage.csv、stats.json
"""
import csv
import collections
import hashlib
import io
import json
import os
import re
import sys
import tempfile
import zipfile

from dexp import Dex

B = "chromahub/rhythm/app/"
SKIP_FN_TOKENS = {"Companion", "WhenMappings", "special", "inlined", "default",
                  "lambda", "INSTANCE", "Kt", "WhenMappings"}


def load_dex(apk_path):
    """把 APK 里的 dex 解到临时目录，返回 Dex 列表。"""
    z = zipfile.ZipFile(apk_path)
    tmp = tempfile.mkdtemp(prefix="rhythm-dex-")
    dxs = []
    for n in z.namelist():
        if re.fullmatch(r"classes\d*\.dex", n):
            p = os.path.join(tmp, n)
            with open(p, "wb") as f:
                f.write(z.read(n))
            dxs.append((n, Dex(p)))
    return z, dxs


def own_class_methods(dxs):
    """类描述符 -> 方法名集合（两 dex 合并）。"""
    out = collections.defaultdict(set)
    for _, dx in dxs:
        mids = dx.method_ids()
        for cname, ms in dx.classes().items():
            cur = 0
            for mdiff, _flags, _code in ms:
                cur += mdiff
                if cur < len(mids):
                    out[cname].add(mids[cur][1])
    return out


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else "Rhythm.apk"
    z, dxs = load_dex(apk)
    os.makedirs("data", exist_ok=True)

    # 1) APK 条目索引
    entries = sorted(z.infolist(), key=lambda i: i.filename)
    with io.open("data/apk-entries.tsv", "w", encoding="utf-8", newline="") as f:
        f.write("path\tsize\tsha256\n")
        for i in entries:
            f.write("%s\t%d\t%s\n" % (i.filename, i.file_size,
                                      hashlib.sha256(z.read(i.filename)).hexdigest()))

    # 2) 自有类 -> 方法
    cm = own_class_methods(dxs)
    own = {k: sorted(v) for k, v in cm.items() if k.startswith("L" + B)}

    rows = []
    for k in own:
        c = k[1:].rstrip(";")
        rows.append((c, c.split("/")[-1], "/".join(c.split("/")[:-1]),
                     "inner" if "$" in c else "top"))
    rows.sort()
    with io.open("data/classes.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["class", "simple", "package", "kind"])
        w.writerows(rows)

    # 3) 从 lambda 内部类还原函数名
    fn_of_file = collections.defaultdict(set)
    for c in own:
        parts = c[1:].rstrip(";").split("$")
        if len(parts) < 2:
            continue
        filecls = parts[0].split("/")[-1]
        pkg = "/".join(parts[0].split("/")[:-1])
        for tok in parts[1:]:
            if (tok and tok[0].isalpha() and tok not in SKIP_FN_TOKENS
                    and not tok.startswith(("access", "special", "inlined"))
                    and not re.fullmatch(r"\d+", tok)):
                fn_of_file[(pkg, filecls)].add(tok)
                break
    with io.open("data/functions-by-file.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["package", "file_class", "functions"])
        for k in sorted(fn_of_file):
            p, fc = k
            w.writerow([p, fc, ";".join(sorted(fn_of_file[k]))])

    # 4) 关键 API 引用
    targets = {
        "anim_core": "Landroidx/compose/animation/core/",
        "anim": "Landroidx/compose/animation/",
        "m3": "Landroidx/compose/material3/",
        "dynanim": "Landroidx/dynamicanimation/",
        "graphics": "Landroidx/compose/ui/graphics/",
    }
    usage = collections.defaultdict(collections.Counter)
    for _, dx in dxs:
        for c, m in dx.method_ids():
            for tag, p in targets.items():
                if c.startswith(p):
                    usage[tag][(c, m)] += 1
    with io.open("data/api-usage.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["group", "member", "refs"])
        for g in sorted(usage):
            for (c, m), n in sorted(usage[g].items(), key=lambda x: (-x[1], x[0])):
                w.writerow([g, "%s#%s" % (c.replace("L", "").replace(";", "").replace("/", "."), m), n])

    # 5) 汇总
    tops = sorted({r[0].split("$")[0] for r in rows})
    pkgc = collections.Counter("/".join(t.split("/")[:-1]) for t in tops)
    stats = {
        "apk": {
            "size": os.path.getsize(apk),
            "sha256": hashlib.sha256(open(apk, "rb").read()).hexdigest(),
            "entries": len(entries),
            "dex": [n for n, _ in dxs],
        },
        "classes": {
            "own_total_incl_inner": len(rows),
            "own_top_level": len(tops),
            "files_with_recovered_functions": len(fn_of_file),
            "recovered_function_names": sum(len(v) for v in fn_of_file.values()),
        },
        "packages_top": pkgc.most_common(25),
        "api_refs": {g: {"distinct": len(c), "refs": sum(c.values())} for g, c in usage.items()},
    }
    json.dump(stats, io.open("data/stats.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print(json.dumps(stats, ensure_ascii=False, indent=1)[:900])


if __name__ == "__main__":
    main()
