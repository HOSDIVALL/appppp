"""DAILY-UX-6 探针：统一走 adb（emulator-5554），每步记录命令与结果。

用法示例:
  python probe.py dump  name          抓 UI dump 到 dumps/name.xml 并打印摘要
  python probe.py ime                打印 mInputShown / mImeWindowVis / ime 顶边
  python probe.py tap X Y
  python probe.py text S
  python probe.py key CODE
  python probe.py shot name
  python probe.py notes              打印 notes.json 的 md5 与条数
"""
import os, re, subprocess, sys, io, json

ADB = "<sdk>/platform-tools/adb.exe"
DEV = "emulator-5554"
BASE = "<workspace>"
PKG = "com.example.myapplicationfirstapp"

def adb(*args, timeout=90):
    cmd = [ADB, "-s", DEV] + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")

def shell(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)

def dump(name):
    shell("uiautomator dump /sdcard/u.xml")
    rc, out, err = adb("exec-out", "cat", "/sdcard/u.xml")
    path = os.path.join(BASE, "dumps", name + ".xml")
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    return path

def nodes(path):
    s = io.open(path, encoding="utf-8").read()
    out = []
    for t in re.findall(r"<node[^>]*>", s):
        def g(k):
            m = re.search(k + r'="([^"]*)"', t)
            return m.group(1) if m else ""
        out.append(dict(text=g("text"), cls=g("class").split(".")[-1], bounds=g("bounds"),
                        focused=g("focused"), clickable=g("clickable"),
                        scrollable=g("scrollable"), pkg=g("package"), res=g("resource-id")))
    return out

def summary(path, only_text=True):
    lines = []
    for n in nodes(path):
        if only_text and not n["text"].strip() and n["focused"] != "true":
            if not (n["clickable"] == "true" or n["scrollable"] == "true"):
                continue
        flags = ",".join([f for f, v in (("FOCUSED", n["focused"]), ("click", n["clickable"]),
                                        ("scroll", n["scrollable"])) if v == "true"])
        lines.append("%-26s %-12s %-22s %s" % (n["bounds"], n["cls"], flags, n["text"][:70]))
    return "\n".join(lines)

def ime():
    _, out, _ = shell("dumpsys input_method")
    m = re.search(r"mInputShown=(\w+)", out)
    v = re.search(r"mImeWindowVis=(\w+)", out)
    _, w, _ = shell("dumpsys window")
    top = re.search(r"type=ime frame=\[\d+,(\d+)\]", w)
    return dict(input_shown=(m.group(1) if m else "?"),
                window_vis=(v.group(1) if v else "?"),
                ime_top=(top.group(1) if top else "none"))

def focused_fields(path):
    return [n for n in nodes(path) if n["focused"] == "true"]

def find_text(path, needle):
    return [n for n in nodes(path) if needle in n["text"]]

def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    a = list(map(int, m.groups()))
    return (a[0] + a[2]) // 2, (a[1] + a[3]) // 2

def main():
    a = sys.argv[1:]
    op = a[0]
    if op == "dump":
        p = dump(a[1]); print(summary(p, only_text=("--all" not in a)))
    elif op == "ime":
        print(json.dumps(ime(), ensure_ascii=False))
    elif op == "tap":
        print(shell("input tap %s %s" % (a[1], a[2]))[2] or "ok")
    elif op == "text":
        print(shell("input text %s" % a[1])[2] or "ok")
    elif op == "key":
        print(shell("input keyevent %s" % a[1])[2] or "ok")
    elif op == "shot":
        shell("screencap -p /sdcard/s.png")
        rc, out, err = adb("exec-out", "cat", "/sdcard/s.png")
        path = os.path.join(BASE, "shots", a[1] + ".png")
        open(path, "wb").write(out)
        print(path, os.path.getsize(path), "bytes")
    elif op == "notes":
        _, out, _ = shell("run-as %s sh -c 'md5sum files/notes.json; grep -c LIME7 files/notes.json; grep -c \"\\\"id\\\"\" files/notes.json'" % PKG)
        print(out.strip())
    else:
        print("unknown op", op)

if __name__ == "__main__":
    main()
