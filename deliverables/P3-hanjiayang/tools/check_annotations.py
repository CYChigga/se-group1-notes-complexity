# -*- coding: utf-8 -*-
"""P3 注释校验脚本。

两件事：
1. 业务逻辑零改动校验：把工作区文件与 git 基线（HEAD）逐文件比对，
   先剥离 // 与 /* */ 注释（正确处理字符串、字符字面量里的注释符号），
   再把连续空白压成一个空格后比较，完全相等即证明代码 token 序列未变。
2. 注释统计：统计新增的纯注释行数（含 /** */ 边界行），
   按文件给出「新增注释行 / 有效代码行 / 比例」，并与 25% 目标对比。

用法：python check_annotations.py [--root <repo 根目录>]
"""
import argparse
import os
import re
import subprocess
import sys

FILES = [
    "src/net/micode/notes/ui/NotesListActivity.java",
    "src/net/micode/notes/ui/NotesPreferenceActivity.java",
    "src/net/micode/notes/ui/NotesListAdapter.java",
    "src/net/micode/notes/ui/NotesListItem.java",
    "src/net/micode/notes/ui/FoldersListAdapter.java",
    "src/net/micode/notes/ui/DropdownMenu.java",
]

SLOC = {
    "NotesListActivity.java": 811,
    "NotesPreferenceActivity.java": 308,
    "NotesListAdapter.java": 143,
    "NotesListItem.java": 95,
    "FoldersListAdapter.java": 50,
    "DropdownMenu.java": 38,
}


def strip_comments(src):
    """去掉 Java 注释，保留字符串/字符字面量原样。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or c == "'":
            quote = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                if ch == "\\" and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if ch == quote:
                    break
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and src[i + 1] == "*":
            i += 2
            while i + 1 < n and not (src[i] == "*" and src[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def normalize(text):
    return re.sub(r"\s+", " ", text).strip()


def git_show_head(root, rel):
    try:
        return subprocess.check_output(
            ["git", "-C", root, "show", "HEAD:" + rel], text=True, encoding="utf-8"
        )
    except subprocess.CalledProcessError:
        return None


def count_added_comment_lines(root, rel):
    """用 git diff 统计新增的纯注释行。"""
    diff = subprocess.check_output(
        ["git", "-C", root, "diff", "-U0", "--", rel],
        text=True, encoding="utf-8",
    )
    added_code, added_comment = 0, 0
    for line in diff.split("\n"):
        if not line.startswith("+") or line.startswith("+++"):
            continue
        body = line[1:].strip()
        if not body:
            continue
        if body.startswith("//") or body.startswith("/*") \
                or body.startswith("*") or body.startswith("*/"):
            added_comment += 1
        else:
            added_code += 1
    return added_comment, added_code


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..")))
    args = ap.parse_args()
    root = args.root

    ok = True
    total_comment = 0
    print("%-30s %8s %8s %8s" % ("文件", "新增注释", "有效代码", "占 25%目标"))
    print("-" * 60)
    for rel in FILES:
        path = os.path.join(root, rel)
        cur = open(path, encoding="utf-8").read()
        base = git_show_head(root, rel)
        same = base is not None and normalize(strip_comments(base)) == normalize(strip_comments(cur))
        if not same:
            ok = False
        added_comment, added_code = count_added_comment_lines(root, rel)
        if added_code:
            ok = False
        total_comment += added_comment
        name = rel.rsplit("/", 1)[-1]
        target = SLOC[name] * 0.25
        print("%-30s %8d %8d %8s" % (name, added_comment, added_code,
                                     "%.0f%%" % (added_comment / target * 100)))
        if not same:
            print("  [FAIL] 去注释后与基线不一致，业务逻辑可能被改动")

    total_sloc = sum(SLOC.values())
    print("-" * 60)
    print("合计新增注释 %d 行 / P3 有效代码 %d 行 = %.1f%%（25%% 目标 %d 行）"
          % (total_comment, total_sloc, total_comment * 100.0 / total_sloc, total_sloc * 0.25))
    print("结论：%s" % ("PASS（逻辑零改动，注释达标）" if ok and total_comment >= total_sloc * 0.25
                     else "FAIL，见上方明细"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
