#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""old-style plist（OpenStep 格式）全量语法校验器。

Xcode 的 pbxproj 解析器遇到第一个语法错误就中止（只报一个行号），
本地必须能一次性报出所有问题，否则要反复push 试错。

校验范围：整个 pbxproj，包括注释、字符串转义、裸词法、数组/字典嵌套。
退出码 0 = 通过，1 = 有错误（逐条打印行号与原因）。
"""
import re
import sys

SPECIAL = set('{}()=;,"')


class PlistError(Exception):
    def __init__(self, msg, line):
        super().__init__(f"第 {line} 行: {msg}")


def tokenize(text):
    """产出 (type, value, line)；type ∈ punct/bareword/qstring/eof。"""
    tokens = []
    i, n = 0, len(text)
    line = 1
    while i < n:
        c = text[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if c in " \t\r":
            i += 1
            continue
        # 行注释
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        # 块注释
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                raise PlistError("块注释未闭合", line)
            line += text.count("\n", i, j)
            i = j + 2
            continue
        # 引号字符串必须先于 SPECIAL 判断（" 也在 SPECIAL 里）
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                if text[j] == "\n":
                    raise PlistError("字符串未闭合（跨行）", line)
                j += 1
            if j >= n:
                raise PlistError("字符串未闭合", line)
            tokens.append(("qstring", text[i + 1:j], line))
            line += text.count("\n", i, j)
            i = j + 1
            continue
        if c in SPECIAL:
            tokens.append(("punct", c, line))
            i += 1
            continue
        # 裸词：除特殊字符与空白外的连续字符
        j = i
        while j < n and text[j] not in SPECIAL and text[j] not in " \t\r\n":
            j += 1
        tokens.append(("bareword", text[i:j], line))
        i = j
    tokens.append(("eof", None, line))
    return tokens


class Parser:
    def __init__(self, tokens):
        self.toks = tokens
        self.pos = 0
        self.errors = []

    def peek(self):
        return self.toks[self.pos]

    def next(self):
        t = self.toks[self.pos]
        self.pos += 1
        return t

    def error(self, msg, line):
        self.errors.append(PlistError(msg, line))

    def parse_value(self):
        t = self.peek()
        if t[0] == "punct" and t[1] == "{":
            self.parse_dict()
        elif t[0] == "punct" and t[1] == "(":
            self.parse_array()
        elif t[0] in ("bareword", "qstring"):
            self.next()
        else:
            self.error(f"意外的符号 {t[1]!r}，期望值", t[2])
            # 跳过出问题的符号，尽量继续收集后续错误
            if t[0] == "punct":
                self.next()

    def parse_dict(self):
        self.next()  # {
        while True:
            t = self.peek()
            if t[0] == "eof":
                self.error("字典未闭合（到达文件末尾）", t[2])
                return
            if t[0] == "punct" and t[1] == "}":
                self.next()
                return
            if t[0] not in ("bareword", "qstring"):
                self.error(f"字典键位置出现 {t[1]!r}", t[2])
                if t[0] == "punct":
                    self.next()
                continue
            key = self.next()
            eq = self.peek()
            if not (eq[0] == "punct" and eq[1] == "="):
                self.error(f"键 {key[1]!r} 后缺少 '='", eq[2])
                continue
            self.next()
            # 值可能是一串（相邻裸词/引号串都算非法，需逐个消化）
            self.parse_value()
            # 吃掉多余的值符号（如相邻引号串），逐个报错
            while self.peek()[0] in ("bareword", "qstring"):
                bad = self.next()
                self.error(f"键 {key[1]!r} 出现相邻的值 {bad[1]!r}（old-style plist 只允许单个值）", bad[2])
            semi = self.peek()
            if not (semi[0] == "punct" and semi[1] == ";"):
                self.error(f"键 {key[1]!r} 的值后缺少 ';'，遇到 {semi[1]!r}", semi[2])
                if semi[0] == "punct" and semi[1] in ("}", ")", ","):
                    continue
                if semi[0] == "eof":
                    return
                self.next()
                continue
            self.next()

    def parse_array(self):
        self.next()  # (
        while True:
            t = self.peek()
            if t[0] == "eof":
                self.error("数组未闭合（到达文件末尾）", t[2])
                return
            if t[0] == "punct" and t[1] == ")":
                self.next()
                return
            if t[0] == "punct" and t[1] == ",":
                self.error("数组中出现多余的 ','", t[2])
                self.next()
                continue
            self.parse_value()
            nxt = self.peek()
            if nxt[0] == "punct" and nxt[1] == ",":
                self.next()
            elif nxt[0] == "punct" and nxt[1] == ")":
                continue  # 允许末尾无逗号
            else:
                self.error(f"数组元素后出现 {nxt[1]!r}，期望 ',' 或 ')'", nxt[2])
                if nxt[0] == "punct":
                    self.next()


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "../DevicePerfMonitor.xcodeproj/project.pbxproj"
    text = open(path, encoding="utf-8").read()
    try:
        tokens = tokenize(text)
    except PlistError as e:
        print(f"[FAIL] {e}")
        return 1
    p = Parser(tokens)
    if p.peek()[0] == "punct" and p.peek()[1] == "{":
        p.parse_dict()
    else:
        p.error("文件根节点不是字典", 1)
    trailing = p.peek()
    if trailing[0] != "eof":
        p.error(f"根字典结束后还有内容: {trailing[1]!r}", trailing[2])
    if p.errors:
        print(f"[FAIL] 发现 {len(p.errors)} 处语法错误:")
        for e in p.errors:
            print(" -", e)
        return 1
    print("[OK] old-style plist 语法校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
