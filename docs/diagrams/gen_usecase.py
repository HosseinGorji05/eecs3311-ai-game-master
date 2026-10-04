"""Generate the UML use-case diagram (use-case-diagram.svg / .png).

Hand laid out so no association crosses a use case. Notation follows the
EECS3311 UML slides: stick-figure actors, a system boundary rectangle, solid
lines for actor associations, dashed <<include>> arrows from the base to the
included use case, dashed <<extend>> arrows from the extending use case to the
base, and extension points written inside the base use case.

Usage: python3 gen_usecase.py   (renders the PNG with Playwright/Chromium)
"""
import math

W, H = 1100, 1375
FONT = "Helvetica, Arial, sans-serif"

# ---- model -----------------------------------------------------------------
# id: (label lines, extension points, (cx, cy), rx, ry)
CX = 470
UC = {
    "UC01": (["UC01 Create Character"], [], (CX, 110), 125, 27),
    "UC03": (["UC03 Talk to NPC"], ["quest offered"], (CX, 205), 125, 42),
    "UC07": (["UC07 Receive and", "Accept Quest"], [], (CX, 330), 115, 32),
    "UC02": (["UC02 Perform Action in", "Natural Language"], ["LLM unavailable"], (CX, 460), 135, 52),
    "UC14": (["UC14 Use Basic Commands", "(LLM Offline)"], [], (CX, 595), 120, 32),
    "UC04": (["UC04 Fight Enemy"], [], (CX, 685), 125, 27),
    "UC05": (["UC05 Manage Inventory"], [], (CX, 760), 125, 27),
    "UC06": (["UC06 Explore World", "and View Map"], [], (CX, 840), 125, 32),
    "UC08": (["UC08 Track Quest Progress"], [], (CX, 920), 125, 27),
    "UC12": (["UC12 View Event History"], [], (CX, 995), 125, 27),
    "UC09": (["UC09 Review Journal"], [], (CX, 1070), 125, 27),
    "UC13": (["UC13 Reach Ending"], [], (CX, 1145), 125, 27),
    "UC10": (["UC10 Save Game"], [], (CX, 1220), 110, 27),
    "UC11": (["UC11 Load Game"], [], (CX, 1290), 110, 27),
}
ACTORS = {  # id: (label lines, stereotype, (x, head_y))
    "Player": (["Player"], None, (70, 640)),
    "LLM": (["LLM Service", "(DeepSeek / Groq)"], "external system", (910, 520)),
    "FS": (["File System"], "external system", (910, 1180)),
}
ASSOC = [("Player", u) for u in UC]
ASSOC += [("LLM", u) for u in ["UC01", "UC02", "UC03", "UC04", "UC07", "UC13"]]
ASSOC += [("FS", "UC10"), ("FS", "UC11")]
INCLUDE = []                                             # base -> included (none)
EXTEND = [("UC07", "UC03", "quest offered"), ("UC14", "UC02", "LLM unavailable")]  # extending -> base
BOX = (190, 55, 790, 1345)  # system boundary x0, y0, x1, y1

# ---- geometry --------------------------------------------------------------
def actor_anchor(aid, toward):
    x, hy = ACTORS[aid][2]
    return (x + (16 if toward[0] > x else -16), hy + 30)

def ellipse_edge(uid, frm):
    (cx, cy), rx, ry = UC[uid][2], UC[uid][3], UC[uid][4]
    dx, dy = frm[0] - cx, frm[1] - cy
    t = 1 / math.sqrt((dx / rx) ** 2 + (dy / ry) ** 2)
    return (cx + dx * t, cy + dy * t)

def inside(uid, p, pad=5):
    (cx, cy), rx, ry = UC[uid][2], UC[uid][3] + pad, UC[uid][4] + pad
    return ((p[0] - cx) / rx) ** 2 + ((p[1] - cy) / ry) ** 2 < 1

def check(seg, ends):
    (x0, y0), (x1, y1) = seg
    bad = []
    for u in UC:
        if u in ends:
            continue
        if any(inside(u, (x0 + (x1 - x0) * k / 400, y0 + (y1 - y0) * k / 400)) for k in range(401)):
            bad.append(u)
    return bad

# ---- drawing ---------------------------------------------------------------
svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="{FONT}">',
       '<defs><marker id="open" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="11" markerHeight="11" orient="auto">'
       '<path d="M1 1 L11 6 L1 11" fill="none" stroke="#222" stroke-width="1.4"/></marker></defs>',
       f'<rect width="{W}" height="{H}" fill="#fff"/>',
       f'<text x="{W/2}" y="30" text-anchor="middle" font-size="17" font-weight="bold">Use-Case Diagram — AI Game Master</text>']
x0, y0, x1, y1 = BOX
svg.append(f'<rect x="{x0}" y="{y0}" width="{x1-x0}" height="{y1-y0}" fill="#FCFCFC" stroke="#222" stroke-width="1.3"/>')
svg.append(f'<text x="{(x0+x1)/2}" y="{y0+22}" text-anchor="middle" font-size="15" font-weight="bold">AI Game Master</text>')

problems, segments, labels, actor_labels = [], [], [], []
for a, u in ASSOC:
    c = UC[u][2]
    p0 = actor_anchor(a, c)
    rx = UC[u][3]
    p1 = (c[0] - rx, c[1]) if p0[0] < c[0] else (c[0] + rx, c[1])
    problems += [f"{a}-{u} crosses {b}" for b in check((p0, p1), {u})]
    segments.append((f"{a}-{u}", p0, p1))
    svg.append(f'<line x1="{p0[0]:.1f}" y1="{p0[1]:.1f}" x2="{p1[0]:.1f}" y2="{p1[1]:.1f}" stroke="#222" stroke-width="1.2"/>')

def dashed(frm, to, label, sub=None):
    c0, c1 = UC[frm][2], UC[to][2]
    p0, p1 = ellipse_edge(frm, c1), ellipse_edge(to, c0)
    problems.extend(f"{frm}->{to} crosses {b}" for b in check((p0, p1), {frm, to}))
    segments.append((f"{frm}->{to}", p0, p1))
    svg.append(f'<line x1="{p0[0]:.1f}" y1="{p0[1]:.1f}" x2="{p1[0]:.1f}" y2="{p1[1]:.1f}" stroke="#222" '
               f'stroke-width="1.2" stroke-dasharray="7 5" marker-end="url(#open)"/>')
    mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
    vertical = abs(p1[0] - p0[0]) < abs(p1[1] - p0[1])
    tx, anchor = (mx + 10, "start") if vertical else (mx, "middle")
    ty = my + 4 if vertical else my - (22 if sub else 8)
    svg.append(f'<text x="{tx:.1f}" y="{ty:.1f}" text-anchor="{anchor}" font-size="12" paint-order="stroke" stroke="#fff" stroke-width="5">«{label}»</text>')
    texts = [f"«{label}»"] + ([f"({sub})"] if sub else [])
    wmax = max(6.6 * len(t) for t in texts)
    bx0 = tx if anchor == "start" else tx - wmax / 2
    labels.append((f"{frm}->{to}", (bx0, ty - 11, bx0 + wmax, ty + 15 * (len(texts) - 1) + 3)))
    if sub:
        svg.append(f'<text x="{tx:.1f}" y="{ty+15:.1f}" text-anchor="{anchor}" font-size="11.5" fill="#444" paint-order="stroke" stroke="#fff" stroke-width="5">({sub})</text>')

for b, i in INCLUDE:
    dashed(b, i, "include")
for e, b, cond in EXTEND:
    dashed(e, b, "extend", cond)

for u, (lines, ext, (cx, cy), rx, ry) in UC.items():
    svg.append(f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="#fff" stroke="#222" stroke-width="1.3"/>')
    if ext:
        top = cy - ry + 20
        for k, t in enumerate(lines):
            svg.append(f'<text x="{cx}" y="{top + 15*k}" text-anchor="middle" font-size="13">{t}</text>')
        sep = top + 15 * (len(lines) - 1) + 8
        half = rx * math.sqrt(max(0, 1 - ((sep - cy) / ry) ** 2))
        svg.append(f'<line x1="{cx-half:.1f}" y1="{sep}" x2="{cx+half:.1f}" y2="{sep}" stroke="#222" stroke-width="1"/>')
        svg.append(f'<text x="{cx}" y="{sep+15}" text-anchor="middle" font-size="11.5" font-weight="bold">Extension points:</text>')
        for k, t in enumerate(ext):
            svg.append(f'<text x="{cx}" y="{sep+29+13*k}" text-anchor="middle" font-size="11.5">{t}</text>')
    else:
        start = cy + 4.5 - 7.5 * (len(lines) - 1)
        for k, t in enumerate(lines):
            svg.append(f'<text x="{cx}" y="{start + 15*k}" text-anchor="middle" font-size="13">{t}</text>')

for aid, (lines, stereo, (x, hy)) in ACTORS.items():
    svg.append(f'<g stroke="#222" stroke-width="1.4" fill="#fff"><circle cx="{x}" cy="{hy}" r="10"/>'
               f'<line x1="{x}" y1="{hy+10}" x2="{x}" y2="{hy+42}"/><line x1="{x-16}" y1="{hy+22}" x2="{x+16}" y2="{hy+22}"/>'
               f'<line x1="{x}" y1="{hy+42}" x2="{x-13}" y2="{hy+64}"/><line x1="{x}" y1="{hy+42}" x2="{x+13}" y2="{hy+64}"/></g>')
    beside = x > CX            # actors right of the system: label beside the figure, clear of every line
    tx, anchor = (x + 24, "start") if beside else (x, "middle")
    y = hy + 26 if beside else hy + 82
    texts = []
    if stereo:
        sy = hy + 8 if beside else hy - 18
        svg.append(f'<text x="{tx}" y="{sy}" text-anchor="{anchor}" font-size="11.5" font-style="italic">«{stereo}»</text>')
        texts.append((sy, 6.4 * (len(stereo) + 2)))
    for k, t in enumerate(lines):
        svg.append(f'<text x="{tx}" y="{y + 15*k}" text-anchor="{anchor}" font-size="13" font-weight="bold">{t}</text>')
        texts.append((y + 15 * k, 7.6 * len(t)))
    for ty, w in texts:
        bx0 = tx if beside else tx - w / 2
        actor_labels.append((aid, (bx0, ty - 11, bx0 + w, ty + 3)))

svg.append("</svg>")
open("use-case-diagram.svg", "w", encoding="utf-8").write("\n".join(svg))

# line-line crossings and lines through labels
def cross(a, b, c, d):
    def orient(p, q, r):
        return (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
    if min(math.dist(a, c), math.dist(a, d), math.dist(b, c), math.dist(b, d)) < 2:
        return False          # lines that meet at a shared end point
    return orient(a, b, c) * orient(a, b, d) < 0 and orient(c, d, a) * orient(c, d, b) < 0
for i in range(len(segments)):
    for j in range(i + 1, len(segments)):
        n1, a, b = segments[i]; n2, c, d = segments[j]
        if cross(a, b, c, d):
            problems.append(f"line {n1} crosses line {n2}")
def seg_hits_box(a, b, box):
    return any(box[0] <= a[0] + (b[0] - a[0]) * k / 400 <= box[2] and box[1] <= a[1] + (b[1] - a[1]) * k / 400 <= box[3]
               for k in range(401))
for owner, box in labels + [("actor " + a, b) for a, b in actor_labels]:
    for n, a, b in segments:
        if n != owner and seg_hits_box(a, b, box):
            problems.append(f"line {n} runs through the label of {owner}")
    for u in UC:
        if any(inside(u, (x, y), pad=0) for x in (box[0], box[2]) for y in (box[1], box[3])):
            problems.append(f"label of {owner} overlaps use case {u}")

if problems:
    print("LAYOUT PROBLEMS:\n  " + "\n  ".join(problems))
else:
    print("layout OK: no line crosses a use case, another line or a label; no label overlaps a use case")

from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch()
    pg = b.new_page(viewport={"width": W, "height": H}, device_scale_factor=2)
    pg.set_content('<html><body style="margin:0">' + "\n".join(svg) + "</body></html>")
    pg.locator("svg").screenshot(path="use-case-diagram.png", timeout=60000)
    b.close()
print("rendered use-case-diagram.svg and use-case-diagram.png")
