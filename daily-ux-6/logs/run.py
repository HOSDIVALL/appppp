"""DAILY-UX-6 对照执行器。所有操作走 emulator-5554；日志逐条记录命令与结果。

python run.py a1|a2|a3   场景 A(+B) 第 n 次
python run.py c1..c3    场景 C
python run.py d1..d3    场景 D
python run.py e1..e3    场景 E
python run.py f1..f3    场景 F
"""
import io, os, re, subprocess, sys, time, json

ADB = "<sdk>/platform-tools/adb.exe"
DEV = "emulator-5554"
BASE = "<workspace>"
PKG = "com.example.myapplicationfirstapp"
LOG = None

def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line)
    LOG.write(line + "\n"); LOG.flush()

def adb(*args, timeout=120):
    cmd = [ADB, "-s", DEV] + list(args)
    p = subprocess.run(cmd, capture_output=True, timeout=timeout)
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")

def shell(cmd, timeout=120, quiet=False):
    rc, out, err = adb("shell", cmd, timeout=timeout)
    if not quiet:
        log("adb shell %s  -> rc=%d %s" % (cmd, rc, (err.strip()[:120] if err.strip() else "")))
    return out

def dump(name):
    shell("uiautomator dump /sdcard/u.xml", quiet=True)
    rc, out, err = adb("exec-out", "cat", "/sdcard/u.xml")
    path = os.path.join(BASE, "dumps", name + ".xml")
    io.open(path, "w", encoding="utf-8", newline="").write(out)
    log("dump %s  (%d bytes)" % (name, os.path.getsize(path)))
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
                        scrollable=g("scrollable"), selected=g("selected")))
    return out

def center(b):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", b)
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2

def ime():
    out = shell("dumpsys input_method", quiet=True)
    m = re.search(r"mInputShown=(\w+)", out)
    v = re.search(r"mImeWindowVis=(\w+)", out)
    w = shell("dumpsys window", quiet=True)
    top = re.search(r"type=ime frame=\[\d+,\d+\]\[\d+,\d+\] visibleFrame=\[\d+,(\d+)\]", w)
    return dict(input_shown=(m.group(1) if m else "?"),
                ime_vis=(v.group(1) if v else "?"),
                ime_top=(int(top.group(1)) if top else None))

def ime_str(d):
    return "mInputShown=%s mImeWindowVis=%s imeTop=%s" % (d["input_shown"], d["ime_vis"], d["ime_top"])

def state(path):
    ns = nodes(path)
    focused = [(n["cls"], n["bounds"], n["text"][:20]) for n in ns if n["focused"] == "true"]
    search = [n for n in ns if n["cls"] == "EditText" and center(n["bounds"])[1] < 700]
    edits = [n for n in ns if n["cls"] == "EditText"]
    subtitle = [n["text"] for n in ns if re.match(r"^(匹配 \d+ 条笔记|共 \d+ 条)", n["text"])]
    items = {n["text"][:10]: n["bounds"] for n in ns if re.match(r"^UX5-\d+", n["text"])}
    return dict(focused=focused, query=(search[0]["text"] if search else None),
                search_focused=bool(search and search[0]["focused"] == "true"),
                edits=[(e["bounds"], e["text"][:24], e["focused"]) for e in edits],
                subtitle=subtitle, items=items)

def report(tag, path, im):
    st = state(path)
    log("%s | %s" % (tag, ime_str(im)))
    log("%s | subtitle=%s query=%r searchFocused=%s" % (tag, st["subtitle"], st["query"], st["search_focused"]))
    log("%s | focused=%s" % (tag, st["focused"]))
    log("%s | editTexts=%s" % (tag, st["edits"]))
    if st["items"]:
        log("%s | visible notes=%s" % (tag, " ".join("%s%s" % (k, v) for k, v in st["items"].items())))
    return st

def coldstart():
    shell("am start -S -W -n %s/.MainActivity" % PKG, quiet=True)
    time.sleep(2.6)
    log("cold start done")

def tap(x, y, wait=1.2):
    shell("input tap %d %d" % (x, y))
    time.sleep(wait)

def text(s, wait=1.5):
    shell("input text %s" % s)
    time.sleep(wait)

def key(c, wait=1.3):
    shell("input keyevent %s" % c)
    time.sleep(wait)

def swipe(x1, y1, x2, y2, ms=900, wait=1.4):
    shell("input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))
    time.sleep(wait)

def shot(name):
    shell("screencap -p /sdcard/s.png", quiet=True)
    p2 = subprocess.run([ADB, "-s", DEV, "exec-out", "cat", "/sdcard/s.png"], capture_output=True, timeout=120)
    out = p2.stdout
    p = os.path.join(BASE, "shots", name + ".png")
    open(p, "wb").write(out)
    log("shot %s (%d bytes)" % (name, os.path.getsize(p)))

def search_on(keyword="LIME7"):
    tap(535, 593, 1.4)
    text(keyword, 1.6)

def first_result(d, prefer=0):
    ns = [n for n in nodes(d) if re.match(r"^UX5-\d+", n["text"])]
    # 过滤掉落在键盘区以下的
    ns = [n for n in ns if center(n["bounds"])[1] < 1450]
    return ns[prefer] if len(ns) > prefer else None

def open_result(d, prefer=0, wait=2.4):
    n = first_result(d, prefer)
    x, y = center(n["bounds"])
    log("tap result %r at (%d,%d)" % (n["text"], x, y))
    tap(x, y, wait)
    return n

def editor_present(path):
    st = state(path)
    return any(b[1] == "231" for b, _, _ in [(e[0], 0, 0) for e in st["edits"]]) or \
           any(e[0].startswith("[42,231]") for e in st["edits"])

def scenario_ab(rep, scroll=False):
    log("=== A%d/B%d begin ===" % (rep, rep))
    coldstart()
    search_on()
    d0 = dump("a%d-1-search" % rep)
    im0 = ime()
    st0 = report("a%d-1-search" % rep, d0, im0)
    assert st0["search_focused"], "搜索框未获焦，场景不成立"
    if scroll:
        swipe(540, 1300, 540, 1050, 900)
        d0 = dump("a%d-2-scrolled" % rep)
        im0 = ime()
        st0 = report("a%d-2-scrolled" % rep, d0, im0)
    shot("a%d-1-search" % rep)
    n = open_result(d0, 0 if not scroll else 1)
    d1 = dump("a%d-3-editor" % rep)
    im1 = ime()
    st1 = report("a%d-3-editor" % rep, d1, im1)
    shot("a%d-2-editor" % rep)
    log("A%d CHECK input_shown=%s (expect false) | editor editTexts=%d | any focused=%s (expect none)"
        % (rep, im1["input_shown"], len(st1["edits"]), st1["focused"]))
    if scroll:
        # 打字探针：若搜索框仍持有输入连接，字符会落进 query
        text("Q", 1.2)
        d1b = dump("a%d-3b-after-type" % rep)
        st1b = report("a%d-3b-after-type" % rep, d1b, ime())
        key("KEYCODE_DEL", 1.0)
        log("A%d typed-probe: editor edits=%s" % (rep, st1b["edits"]))
    key("KEYCODE_BACK", 2.0)
    d2 = dump("a%d-4-back" % rep)
    im2 = ime()
    st2 = report("a%d-4-back" % rep, d2, im2)
    shot("a%d-3-back" % rep)
    same_query = (st2["query"] == st0["query"])
    same_items = (st2["items"] == st0["items"])
    log("B%d CHECK back-to-list=%s query_same=%s items_same=%s input_shown=%s search_focused=%s"
        % (rep, st2["query"] is not None, same_query, same_items, im2["input_shown"], st2["search_focused"]))
    if not same_items:
        log("B%d items before=%s" % (rep, st0["items"]))
        log("B%d items after =%s" % (rep, st2["items"]))

def scenario_c(rep):
    log("=== C%d begin ===" % rep)
    coldstart()
    search_on()
    key("KEYCODE_BACK", 1.6)  # 收键盘，保留搜索态
    d0 = dump("c%d-1-kb-hidden" % rep)
    st0 = report("c%d-1-kb-hidden" % rep, d0, ime())
    shot("c%d-1-kb-hidden" % rep)
    n = open_result(d0, 0)
    d1 = dump("c%d-2-editor" % rep)
    im1 = ime()
    st1 = report("c%d-2-editor" % rep, d1, im1)
    shot("c%d-2-editor" % rep)
    key("KEYCODE_BACK", 2.0)
    d2 = dump("c%d-3-back" % rep)
    im2 = ime()
    st2 = report("c%d-3-back" % rep, d2, im2)
    log("C%d CHECK kb_hidden_before=%s editor_kb=%s back_to_list=%s query_same=%s items_same=%s"
        % (rep, st0["search_focused"] is not None and im1 is not None,
           im1["input_shown"], st2["query"] is not None,
           st2["query"] == st0["query"], st2["items"] == st0["items"]))

def body_text(path):
    for n in nodes(path):
        if n["cls"] == "EditText" and n["text"].startswith("主题："):
            return n["text"]
    return None

def scenario_d(rep):
    log("=== D%d begin ===" % rep)
    coldstart()
    search_on()
    d0 = dump("d%d-1-search" % rep)
    open_result(d0, 0)
    d1 = dump("d%d-2-editor" % rep)
    im1 = ime()
    st1 = report("d%d-2-editor" % rep, d1, im1)
    log("D%d editor idle: kb=%s body=%r" % (rep, im1["input_shown"], body_text(d1)))
    # 1) 点正文第一行：应唤起键盘、正文获焦
    body = [n for n in nodes(d1) if n["cls"] == "EditText" and n["text"].startswith("主题：")]
    b = body[0]["bounds"]
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    x1, y1, x2, y2 = map(int, m.groups())
    tap(300, y1 + 60, 1.8)
    d2 = dump("d%d-3-body-focus" % rep)
    im2 = ime()
    st2 = report("d%d-3-body-focus" % rep, d2, im2)
    shot("d%d-1-body-focus" % rep)
    text("Z", 1.4)
    d3 = dump("d%d-4-typed-head" % rep)
    st3 = report("d%d-4-typed-head" % rep, d3, ime())
    log("D%d typed-head body=%r" % (rep, body_text(d3)))
    key("KEYCODE_DEL", 1.2)
    d3b = dump("d%d-4b-del" % rep)
    log("D%d after-del body=%r" % (rep, body_text(d3b)))
    # 2) 点正文块内末尾空白：仍可编辑，光标落在末尾
    body2 = [n for n in nodes(d3b) if n["cls"] == "EditText" and n["text"].startswith("主题：")]
    m2 = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", body2[0]["bounds"])
    by2 = int(m2.group(4))
    tail_y = by2 - 24
    log("tail tap y=%d (body block %s)" % (tail_y, body2[0]["bounds"]))
    tap(540, tail_y, 1.8)
    d4 = dump("d%d-5-tail-tap" % rep)
    im4 = ime()
    st4 = report("d%d-5-tail-tap" % rep, d4, im4)
    shot("d%d-2-tail" % rep)
    text("Y", 1.4)
    d5 = dump("d%d-6-typed-tail" % rep)
    st5 = report("d%d-6-typed-tail" % rep, d5, ime())
    log("D%d typed-tail body=%r" % (rep, body_text(d5)))
    key("KEYCODE_DEL", 1.0)
    d5b = dump("d%d-6b-del" % rep)
    log("D%d after-del-tail body=%r" % (rep, body_text(d5b)))
    key("KEYCODE_BACK", 1.4)   # 收键盘
    key("KEYCODE_BACK", 2.0)   # 离开编辑页
    d6 = dump("d%d-7-back" % rep)
    st6 = report("d%d-7-back" % rep, d6, ime())
    log("D%d CHECK head_kb=%s head_focus=%s tail_kb=%s back_to_list=%s"
        % (rep, im2["input_shown"], st2["focused"], im4["input_shown"],
           st6["query"] == "LIME7"))

def count_notes():
    out = shell("run-as %s sh -c 'wc -c files/notes.json'" % PKG, quiet=True)
    return out.strip()

def scenario_e(rep):
    log("=== E%d begin ===" % rep)
    coldstart()
    before = count_notes()
    log("E%d notes.json before: %s" % (rep, before))
    tap(954, 2221, 2.8)   # 导航栏 + ：新建笔记
    d1 = dump("e%d-1-new-editor" % rep)
    im1 = ime()
    st1 = report("e%d-1-new-editor" % rep, d1, im1)
    shot("e%d-1-new" % rep)
    st = state(d1)
    body = [e for e in st["edits"] if not e[0].startswith("[42,231]")]
    log("E%d CHECK new_note_kb=%s focused=%s body=%s title_focused=%s"
        % (rep, im1["input_shown"], st["focused"],
           body[0][0] if body else None,
           any(e[0].startswith("[42,231]") and e[2] == "true" for e in st["edits"])))
    key("KEYCODE_BACK", 1.4)   # 收键盘
    key("KEYCODE_BACK", 2.0)   # 离开（空白新笔记应被丢弃）
    d2 = dump("e%d-2-back" % rep)
    st2 = report("e%d-2-back" % rep, d2, ime())
    after = count_notes()
    log("E%d notes.json after: %s | back_to_list=%s" % (rep, after, st2["subtitle"]))

def scenario_f(rep):
    log("=== F%d begin ===" % rep)
    coldstart()
    d0 = dump("f%d-1-list" % rep)
    st0 = report("f%d-1-list" % rep, d0, ime())
    ns = [n for n in nodes(d0) if re.match(r"^UX5-\d+", n["text"])]
    x, y = center(ns[1]["bounds"])
    log("tap list item %r at (%d,%d)" % (ns[1]["text"], x, y))
    tap(x, y, 2.4)
    d1 = dump("f%d-2-editor" % rep)
    im1 = ime()
    st1 = report("f%d-2-editor" % rep, d1, im1)
    shot("f%d-1-editor" % rep)
    key("KEYCODE_BACK", 2.0)
    d2 = dump("f%d-3-back" % rep)
    st2 = report("f%d-3-back" % rep, d2, ime())
    log("F%d CHECK editor_kb=%s focused=%s back_to_list=%s"
        % (rep, im1["input_shown"], st1["focused"], st2["subtitle"]))

def main():
    global LOG
    tag = sys.argv[1]
    LOG = io.open(os.path.join(BASE, "logs", "run-%s.log" % tag), "w", encoding="utf-8")
    if tag.startswith("a"):
        scenario_ab(int(tag[1:]), scroll=(tag == "a3"))
    elif tag.startswith("c"):
        scenario_c(int(tag[1:]))
    elif tag.startswith("d"):
        scenario_d(int(tag[1:]))
    elif tag.startswith("e"):
        scenario_e(int(tag[1:]))
    elif tag.startswith("f"):
        scenario_f(int(tag[1:]))
    log("=== %s end ===" % tag)
    LOG.close()

if __name__ == "__main__":
    main()
