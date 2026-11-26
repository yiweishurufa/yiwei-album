#!/usr/bin/env python3
"""打印 CHANGELOG.md 里某个版本那一节（CI 发布 nightly 时写进 release 说明和 version.json 的 notes）。

用法：changelog_section.py [--plain] CHANGELOG.md 1.7.17
  默认输出 Markdown（### 新增 / - 条目），--plain 输出给 App 更新窗口看的纯文字（【新增】/ · 条目，去掉 ** 和 `）。
找不到这个版本时什么也不输出，退出码 1（CI 里 || true，退回旧的提交说明）。
"""
import re
import sys


def section(md: str, version: str) -> str:
    out, on = [], False
    for line in md.splitlines():
        m = re.match(r"^##\s+([0-9][0-9A-Za-z.\-]*)", line)
        if line.startswith("## "):
            if on:
                break
            on = bool(m) and m.group(1) == version
            continue
        if on:
            out.append(line.rstrip())
    return "\n".join(out).strip()


def plain(sec: str) -> str:
    lines = []
    for line in sec.splitlines():
        t = line.replace("**", "").replace("`", "")
        if t.startswith("### "):
            lines.append("【%s】" % t[4:].strip())
        elif t.lstrip().startswith(("- ", "* ")):
            lines.append("· " + t.lstrip()[2:].strip())
        elif t.strip() and lines and line.startswith(" "):
            lines[-1] += t.strip()
        elif t.strip():
            lines.append(t.strip())
    return "\n".join(lines)


def main(argv):
    as_plain = "--plain" in argv
    args = [a for a in argv if a != "--plain"]
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    md = open(args[0], encoding="utf-8").read()
    sec = section(md, args[1])
    if not sec:
        return 1
    print(plain(sec) if as_plain else sec)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
