#!/usr/bin/env python3
"""Kotlin lexical scanner: locate string literals (incl. templates) with safe spans.

Yields Literal records: start/end offsets in source, raw source text, decoded value,
whether it contains interpolation, and the raw source of each interpolation expression.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field


@dataclass
class Interp:
    expr: str  # raw source inside ${...}
    start: int  # absolute offset of expr start (after "${")
    end: int  # absolute offset of expr end (before "}")


@dataclass
class Literal:
    start: int
    end: int
    raw: str  # exact source text incl. quotes
    quote: str  # '"', '"""'
    value: str  # decoded logical value (escapes resolved; ${} kept as-is)
    interps: list[Interp] = field(default_factory=list)

    @property
    def is_template(self) -> bool:
        return bool(self.interps)


def _decode_escapes(body: str) -> str:
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == "\\" and i + 1 < len(body):
            n = body[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\", "$": "$"}
            out.append(mapping.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def scan(text: str) -> list[Literal]:
    """Return all string literals in Kotlin source, respecting comments and nesting."""
    lits: list[Literal] = []
    n = len(text)
    i = 0

    def skip_line_comment(p: int) -> int:
        while p < n and text[p] != "\n":
            p += 1
        return p

    def skip_block_comment(p: int) -> int:
        # p at "/"; assumes text[p:p+2] == "/*"
        p += 2
        while p < n - 1 and text[p : p + 2] != "*/":
            p += 1
        return min(p + 2, n)

    def read_string(p: int, quote: str) -> tuple[int, Literal]:
        """p at opening quote; return (end_after_closing_quote, Literal)."""
        qn = len(quote)
        start = p
        p += qn
        body_start = p
        interps: list[Interp] = []
        while p < n:
            c = text[p]
            if c == "\\":
                p += 2
                continue
            if quote == '"' and c == "$" and p + 1 < n:
                nxt = text[p + 1]
                if nxt == "{":
                    expr_start = p + 2
                    depth = 1
                    q = expr_start
                    while q < n and depth > 0:
                        ch = text[q]
                        if ch == "{":
                            depth += 1
                        elif ch == "}":
                            depth -= 1
                            if depth == 0:
                                break
                        elif ch == '"':
                            # nested string inside expression
                            if text[q : q + 3] == '"""':
                                _, inner = read_string(q, '"""')
                                q = inner.end
                                continue
                            _, inner = read_string(q, '"')
                            q = inner.end
                            continue
                        elif ch == "/" and q + 1 < n and text[q + 1] == "/":
                            q = skip_line_comment(q)
                            continue
                        elif ch == "/" and q + 1 < n and text[q + 1] == "*":
                            q = skip_block_comment(q)
                            continue
                        q += 1
                    interps.append(Interp(text[expr_start:q], expr_start, q))
                    p = q + 1
                    continue
                if nxt.isalpha() or nxt == "_":
                    # simple $var interpolation: treat as one interp spanning the identifier
                    q = p + 1
                    while q < n and (text[q].isalnum() or text[q] == "_"):
                        q += 1
                    interps.append(Interp(text[p + 1 : q], p + 1, q))
                    p = q
                    continue
                p += 1  # "$" followed by anything else is a literal dollar sign
                continue
            if c == quote[0]:
                if qn == 1:
                    break
                # raw string: a run of N>=3 quotes terminates with the LAST 3
                if text[p : p + qn] == quote:
                    r = p
                    while r < n and text[r] == '"':
                        r += 1
                    run = r - p
                    if run >= 3:
                        body_end = p + (run - 3)
                        close_end = p + run
                        raw = text[start:close_end]
                        body = text[body_start:body_end]
                        return close_end, Literal(start, close_end, raw, quote, body, interps)
                    p = r
                    continue
            p += 1
        raw = text[start : p + qn]
        body = text[body_start:p]
        value = _decode_escapes(body) if qn == 1 else body
        return p + qn, Literal(start, p + qn, raw, quote, value, interps)

    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            i = skip_line_comment(i)
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i = skip_block_comment(i)
            continue
        if text[i : i + 3] == '"""':
            i, lit = read_string(i, '"""')
            lits.append(lit)
            continue
        if c == '"':
            i, lit = read_string(i, '"')
            lits.append(lit)
            continue
        if c == "'":
            # char literal: skip
            j = i + 1
            if j < n and text[j] == "\\":
                j += 2
            else:
                j += 1
            if j < n and text[j] == "'":
                i = j + 1
                continue
            i += 1
            continue
        i += 1
    return lits


KEY_LIKE = re.compile(r"_[0-9a-f]{10}$")
STRUCT_LIKE = re.compile(r"^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$")
