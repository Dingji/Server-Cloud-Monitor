"""HTML -> Markdown conversion (note import from .html + rich-text paste).

Uses html2text with tuned options, then normalizes its output so the
existing editor/renderer treats imported content exactly like hand-written
Markdown:
  * lists are dedented (common leading space removed, nesting preserved)
  * tables get standard pipes (pad_tables)
  * oversized inline data: images are dropped instead of bloating the DB
"""
import re

import html2text

_LIST_LINE = re.compile(r"^(\s+)(?:[-*+]|\d+[.)])\s+\S")
_DATA_URL = re.compile(r"data:[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{500,}")


def _dedent_list_blocks(md: str) -> str:
    """Remove the common indent html2text adds to top-level lists while
    keeping relative nesting depth intact."""
    lines = md.split("\n")
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if _LIST_LINE.match(line):
            block = []
            while (
                i < len(lines)
                and lines[i].strip()
                and (_LIST_LINE.match(lines[i]) or lines[i][:1] in (" ", "\t"))
            ):
                block.append(lines[i])
                i += 1
            indents = [len(l) - len(l.lstrip(" ")) for l in block if _LIST_LINE.match(l)]
            cut = min(indents) if indents else 0
            for l in block:
                lead = len(l) - len(l.lstrip(" "))
                take = min(cut, lead)
                out.append(l[take:])
        else:
            out.append(line)
            i += 1
    return "\n".join(out)


def html_to_markdown(html_text: str) -> str:
    h = html2text.HTML2Text()
    h.body_width = 0            # never hard-wrap (Chinese text!)
    h.unicode_snob = True       # real Unicode instead of entities
    h.ignore_images = False     # keep ![](…)
    h.ignore_links = False      # keep [](…)
    h.ignore_emphasis = False   # keep **bold** / _italic_
    h.ignore_code = False
    h.bypass_tables = False     # convert tables to Markdown
    h.pad_tables = True         # | pipes | on both sides
    h.single_line_break = False # paragraphs separated by blank lines
    md = h.handle(str(html_text or ""))
    md = _dedent_list_blocks(md)
    md = _DATA_URL.sub("[内嵌图片过大，已省略]", md)
    md = re.sub(r"\n{4,}", "\n\n\n", md)
    return md.strip()
