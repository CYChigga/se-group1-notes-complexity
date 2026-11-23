# -*- coding: utf-8 -*-
"""P3 图表生成脚本：输出可编辑 .drawio 源文件 + 300dpi PNG。

同一份节点/连线定义同时渲染两种格式，避免「图文不一致」：
- PNG：Pillow 绘制，3 倍缩放并按 300dpi 写入元数据，用于报告排版；
- .drawio：mxGraphModel XML，可直接在 draw.io / diagrams.net 打开继续编辑。

坐标单位为「设计像素」，缩放因子 SCALE 只作用于 PNG。
"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

SCALE = 3
FONT = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"
OUT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "03_流程与图表"))

COLORS = {
    "start": ("#DEEBF7", "#2E75B6"),
    "end": ("#DEEBF7", "#2E75B6"),
    "process": ("#F7F7F7", "#7F7F7F"),
    "decision": ("#FFF2CC", "#BF9000"),
    "io": ("#E2EFDA", "#548235"),
    "layer_ui": ("#E8F0FE", "#3B6FD4"),
    "layer_domain": ("#FDF3E7", "#C07C1F"),
    "layer_sync": ("#EAF6EC", "#3F8A4E"),
    "layer_data": ("#F1ECF7", "#6B4C9A"),
}


def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, int(size * SCALE))


def wrap(draw, text, fnt, max_w):
    """按像素宽度折行，兼顾中文无空格断行。"""
    lines, cur = [], ""
    for ch in text:
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        trial = cur + ch
        if draw.textlength(trial, font=fnt) > max_w and cur:
            lines.append(cur)
            cur = ch
        else:
            cur = trial
    if cur:
        lines.append(cur)
    return lines


def draw_center_text(draw, box, text, fnt, color="#1A1A1A"):
    x0, y0, x1, y1 = [v * SCALE for v in box]
    lines = wrap(draw, text, fnt, (x1 - x0) - 18 * SCALE)
    lh = fnt.size + 6 * SCALE
    total = lh * len(lines)
    cy = (y0 + y1) / 2 - total / 2
    for i, ln in enumerate(lines):
        w = draw.textlength(ln, font=fnt)
        draw.text(((x0 + x1 - w) / 2, cy + i * lh), ln, font=fnt, fill=color)


def arrow(draw, pts, color="#404040", width=2, head=10):
    s = [(p[0] * SCALE, p[1] * SCALE) for p in pts]
    draw.line(s, fill=color, width=max(1, int(width * SCALE / 1.5)), joint="curve")
    (x1, y1), (x2, y2) = s[-2], s[-1]
    ang = math.atan2(y2 - y1, x2 - x1)
    h = head * SCALE / 1.5
    p1 = (x2 - h * math.cos(ang - 0.4), y2 - h * math.sin(ang - 0.4))
    p2 = (x2 - h * math.cos(ang + 0.4), y2 - h * math.sin(ang + 0.4))
    draw.polygon([(x2, y2), p1, p2], fill=color)


def render_png(spec, path):
    W, H = spec["size"]
    img = Image.new("RGB", (W * SCALE, H * SCALE), "white")
    d = ImageDraw.Draw(img)

    if spec.get("title"):
        f = font(21, True)
        d.text(((W / 2) * SCALE - d.textlength(spec["title"], font=f) / 2, 20 * SCALE),
               spec["title"], font=f, fill="#111111")

    for node in spec["nodes"]:
        x0, y0, x1, y1 = [v * SCALE for v in node["box"]]
        fill, stroke = COLORS[node["kind"]]
        lw = max(1, int(2 * SCALE / 1.5))
        if node["kind"] == "decision":
            cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
            d.polygon([(cx, y0), (x1, cy), (cx, y1), (x0, cy)], fill=fill,
                      outline=stroke, width=lw)
        else:
            r = (y1 - y0) / 2 if node["kind"] in ("start", "end") else 12 * SCALE
            d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=fill,
                                outline=stroke, width=lw)
        draw_center_text(d, node["box"], node["text"], font(node.get("fs", 13)))

    for e in spec.get("edges", []):
        arrow(d, e["pts"], color=e.get("color", "#404040"))
        if e.get("label"):
            lx, ly = e["lbox"]
            f = font(11)
            lw = d.textlength(e["label"], font=f) + 6 * SCALE
            d.rectangle([lx * SCALE, ly * SCALE, lx * SCALE + lw, ly * SCALE + 17 * SCALE],
                        fill="white")
            d.text((lx * SCALE + 3 * SCALE, ly * SCALE + 1 * SCALE), e["label"], font=f,
                   fill="#333333")

    img.save(path, dpi=(300, 300))
    return path


def render_drawio(spec, path, graph_name):
    def style(node):
        fill, stroke = COLORS[node["kind"]]
        base = ("whiteSpace=wrap;html=1;fillColor=%s;strokeColor=%s;fontSize=%d;"
                "strokeWidth=1.5;" % (fill, stroke, node.get("fs", 13)))
        if node["kind"] == "decision":
            return "rhombus;" + base
        if node["kind"] in ("start", "end"):
            return "rounded=1;arcSize=50;" + base
        return "rounded=1;arcSize=12;" + base

    cells = []
    for node in spec["nodes"]:
        x0, y0, x1, y1 = node["box"]
        cells.append(
            '        <mxCell id="%s" value="%s" style="%s" vertex="1" parent="1">\n'
            '          <mxGeometry x="%d" y="%d" width="%d" height="%d" as="geometry" />\n'
            '        </mxCell>' % (node["id"],
                                  node["text"].replace("&", "&amp;").replace("<", "&lt;"),
                                  style(node), x0, y0, x1 - x0, y1 - y0))
    for i, e in enumerate(spec.get("edges", [])):
        pts = e["pts"]
        way = "".join('<mxPoint x="%d" y="%d" />' % (p[0], p[1]) for p in pts[1:-1])
        cells.append(
            '        <mxCell id="e%d" value="%s" style="endArrow=block;html=1;rounded=0;" '
            'edge="1" parent="1">\n'
            '          <mxGeometry relative="1" as="geometry">\n'
            '            <mxPoint x="%d" y="%d" as="sourcePoint" />\n'
            '            <mxPoint x="%d" y="%d" as="targetPoint" />\n'
            '            <Array as="points">%s</Array>\n'
            '          </mxGeometry>\n'
            '        </mxCell>' % (i, e.get("label", ""), pts[0][0], pts[0][1],
                                  pts[-1][0], pts[-1][1], way))

    xml = ('<mxfile host="app.diagrams.net">\n'
           '  <diagram name="%s">\n'
           '    <mxGraphModel dx="900" dy="600" grid="1" gridSize="10" guides="1" page="1" '
           'pageWidth="%d" pageHeight="%d">\n'
           '      <root>\n'
           '        <mxCell id="0" />\n'
           '        <mxCell id="1" parent="0" />\n'
           '%s\n'
           '      </root>\n'
           '    </mxGraphModel>\n'
           '  </diagram>\n'
           '</mxfile>\n') % (graph_name, spec["size"][0], spec["size"][1], "\n".join(cells))
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(xml)
    return path


# ---------------------------------------------------------------------------
# 图P3-1 模块组织结构图（box = x0, y0, x1, y1）
# ---------------------------------------------------------------------------
ORG = {
    "size": (1120, 800),
    "title": "图P3-1  小米便签模块组织结构与依赖关系（框内为 文件数 / SLOC / 占比）",
    "nodes": [
        {"id": "ui", "box": (60, 90, 460, 200), "kind": "layer_ui",
         "text": "ui  表现层\n14 文件 / 3056 SLOC / 41.3%\n列表页·编辑页·设置页·提醒·日期选择", "fs": 13},
        {"id": "widget", "box": (520, 90, 760, 200), "kind": "layer_ui",
         "text": "widget  桌面小部件\n3 文件 / 145 SLOC / 2.0%\n2x 与 4x 两种尺寸", "fs": 13},

        {"id": "model", "box": (60, 280, 360, 380), "kind": "layer_domain",
         "text": "model  领域模型\n2 文件 / 455 SLOC / 6.2%\nNote·WorkingNote 状态机", "fs": 13},
        {"id": "tool", "box": (430, 280, 680, 380), "kind": "layer_domain",
         "text": "tool  工具\n4 文件 / 678 SLOC / 9.2%\n备份·数据变换·资源解析", "fs": 13},
        {"id": "gtask_remote", "box": (740, 280, 1040, 380), "kind": "layer_sync",
         "text": "gtask.remote  同步\n4 文件 / 1264 SLOC / 17.1%\n会话·增量同步·SyncService", "fs": 13},
        {"id": "gtask_exc", "box": (430, 470, 680, 560), "kind": "layer_sync",
         "text": "gtask.exception\n2 文件 / 26 SLOC / 0.4%\n网络/动作异常", "fs": 13},
        {"id": "gtask_data", "box": (740, 470, 1040, 560), "kind": "layer_sync",
         "text": "gtask.data  同步模型\n6 文件 / 1143 SLOC / 15.5%\nTask·TaskList·SqlNote", "fs": 13},

        {"id": "data", "box": (60, 640, 680, 750), "kind": "layer_data",
         "text": "data  数据层\n4 文件 / 628 SLOC / 8.5%\nContentProvider·SQLite·URI 契约", "fs": 13},
    ],
    "edges": [
        {"pts": [(200, 200), (200, 280)], "label": "状态读写", "lbox": (208, 232)},
        {"pts": [(380, 200), (380, 240), (555, 240), (555, 280)], "label": "格式化", "lbox": (392, 218)},
        {"pts": [(460, 145), (520, 145)], "label": "刷新", "lbox": (470, 118)},
        {"pts": [(440, 200), (440, 245), (890, 245), (890, 280)], "label": "启停同步", "lbox": (700, 222)},
        {"pts": [(760, 145), (1090, 145), (1090, 695), (680, 695)], "label": "读便签", "lbox": (880, 665)},
        {"pts": [(360, 330), (430, 330)], "label": "工具调用", "lbox": (365, 305)},
        {"pts": [(210, 380), (210, 640)], "label": "增删改查", "lbox": (218, 500)},
        {"pts": [(430, 355), (395, 355), (395, 615), (455, 615), (455, 640)], "label": "批量操作", "lbox": (330, 560)},
        {"pts": [(890, 380), (890, 470)], "label": "组装任务", "lbox": (898, 415)},
        {"pts": [(830, 380), (830, 430), (600, 430), (600, 470)], "label": "抛出异常", "lbox": (700, 405)},
        {"pts": [(890, 560), (890, 610), (700, 610), (700, 640)], "label": "映射为本地行", "lbox": (735, 585)},
    ],
}

# ---------------------------------------------------------------------------
# 图P3-2 便签列表查询与渲染流程
# ---------------------------------------------------------------------------
LIST_FLOW = {
    "size": (1080, 1080),
    "title": "图P3-2  便签列表查询与渲染流程（NotesListActivity → NotesListAdapter → NotesListItem）",
    "nodes": [
        {"id": "s", "box": (380, 70, 700, 112), "kind": "start",
         "text": "进入列表页 / 从编辑页返回", "fs": 13},
        {"id": "n1", "box": (380, 132, 700, 180), "kind": "process",
         "text": "onStart() → startAsyncNotesListQuery()", "fs": 13},
        {"id": "d1", "box": (370, 200, 710, 288), "kind": "decision",
         "text": "mCurrentFolderId\n== ID_ROOT_FOLDER ?", "fs": 12},
        {"id": "n2", "box": (60, 320, 400, 415), "kind": "process",
         "text": "selection = ROOT_FOLDER_SELECTION\n排除 TYPE_SYSTEM；通话记录文件夹\n仅在 NOTES_COUNT > 0 时出现", "fs": 12},
        {"id": "n3", "box": (680, 320, 1020, 415), "kind": "process",
         "text": "selection = NORMAL_SELECTION\nPARENT_ID = mCurrentFolderId\n子文件夹内条目全部列出", "fs": 12},
        {"id": "n4", "box": (300, 445, 780, 525), "kind": "io",
         "text": "AsyncQueryHandler.startQuery()\nURI = CONTENT_NOTE_URI；PROJECTION = NoteItemData.PROJECTION\n排序：TYPE DESC, MODIFIED_DATE DESC", "fs": 12},
        {"id": "n5", "box": (300, 550, 780, 600), "kind": "process",
         "text": "后台线程经 NotesProvider 查询 SQLite", "fs": 13},
        {"id": "n6", "box": (300, 625, 780, 712), "kind": "decision",
         "text": "onQueryComplete()\ntoken = 便签列表令牌 ?", "fs": 12},
        {"id": "n7", "box": (60, 742, 470, 822), "kind": "process",
         "text": "mNotesListAdapter.changeCursor(cursor)\ncalcNotesCount() 重算便签总数", "fs": 12},
        {"id": "e1", "box": (820, 742, 1040, 812), "kind": "end",
         "text": "弹出文件夹选择框\n（FOLDER_LIST_QUERY_TOKEN）", "fs": 11},
        {"id": "n8", "box": (180, 852, 900, 932), "kind": "process",
         "text": "NotesListItem.bind() 按条目类型选择渲染分支\n（多选态下仅便签显示复选框）", "fs": 12},
        {"id": "b1", "box": (40, 972, 280, 1050), "kind": "process",
         "text": "通话记录文件夹\n标题 + 条目数", "fs": 11},
        {"id": "b2", "box": (300, 972, 540, 1050), "kind": "process",
         "text": "通话记录条目\n副标题 = 来电人", "fs": 11},
        {"id": "b3", "box": (560, 972, 800, 1050), "kind": "process",
         "text": "普通文件夹\n标题 + 条目数", "fs": 11},
        {"id": "b4", "box": (820, 972, 1060, 1050), "kind": "process",
         "text": "普通便签\n摘要 + 提醒图标", "fs": 11},
    ],
    "edges": [
        {"pts": [(540, 112), (540, 132)]},
        {"pts": [(540, 180), (540, 200)]},
        {"pts": [(370, 244), (230, 244), (230, 320)], "label": "是", "lbox": (272, 222)},
        {"pts": [(710, 244), (850, 244), (850, 320)], "label": "否", "lbox": (762, 222)},
        {"pts": [(230, 415), (230, 485), (300, 485)]},
        {"pts": [(850, 415), (850, 485), (780, 485)]},
        {"pts": [(540, 525), (540, 550)]},
        {"pts": [(540, 600), (540, 625)]},
        {"pts": [(300, 668), (265, 668), (265, 742)], "label": "是", "lbox": (212, 686)},
        {"pts": [(780, 668), (930, 668), (930, 742)], "label": "否", "lbox": (800, 686)},
        {"pts": [(265, 822), (265, 852)]},
        {"pts": [(180, 892), (120, 892), (120, 972)]},
        {"pts": [(400, 932), (400, 972)]},
        {"pts": [(660, 932), (660, 972)]},
        {"pts": [(920, 932), (920, 972)]},
    ],
}


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, spec, png, drawio in [
        ("org", ORG, "图P3-1_模块组织结构图.png", "图P3-1_模块组织结构图.drawio"),
        ("flow", LIST_FLOW, "图P3-2_便签列表查询流程.png", "图P3-2_便签列表查询流程.drawio"),
    ]:
        p1 = render_png(spec, os.path.join(OUT_DIR, png))
        p2 = render_drawio(spec, os.path.join(OUT_DIR, drawio), name)
        print("生成:", os.path.basename(p1), "|", os.path.basename(p2))


if __name__ == "__main__":
    main()
