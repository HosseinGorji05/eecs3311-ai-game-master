"""Geometric check of every inheritance / realization arrowhead in the class diagrams.

UML rule (EECS3311 UML I slides 21 and 23): the line starts at the subclass or
implementing class and the hollow triangle sits at the superclass or interface,
its tip touching that box and pointing into it.

For every hollow triangle in class-*.svg this script finds the tip (the vertex
shared by the two long sides) and checks that
  1. the tip lies on the border of a class box (within TOL pixels), and
  2. the triangle's base lies outside that box, i.e. it points INTO the box.
It also checks that the number of triangles equals the number of
inheritance/realization relationships drawn in that view.

Usage: python3 check_arrowheads.py      (after rendering the SVGs)
"""
import glob, math, re, sys

TOL = 2.0

def boxes(svg):
    out = {}
    for m in re.finditer(r'<g class="entity" data-entity="([^"]+)"[^>]*>(.*?)</g>', svg, re.S):
        r = re.search(r'<rect[^>]*height="([\d.]+)"[^>]*width="([\d.]+)" x="([\d.]+)" y="([\d.]+)"', m.group(2))
        if r:
            h, w, x, y = map(float, r.groups())
            out[m.group(1)] = (x, y, x + w, y + h)
    return out

def triangles(svg):
    for p in re.findall(r'<polygon fill="(?:#FFFFFF|none)" points="([^"]+)"', svg):
        v = [tuple(map(float, xy)) for xy in zip(*[iter(p.split(","))] * 2)]
        if len(v) == 4 and v[0] == v[-1]:
            yield v[:3]

def tip_and_base(t):
    d = lambda a, b: math.dist(a, b)
    for i in range(3):
        a, b, c = t[i], t[(i + 1) % 3], t[(i + 2) % 3]
        if abs(d(a, b) - d(a, c)) < 0.5 and d(a, b) > d(b, c):
            return a, ((b[0] + c[0]) / 2, (b[1] + c[1]) / 2)
    return None, None

def on_border(p, b):
    x0, y0, x1, y1 = b
    inside_x = x0 - TOL <= p[0] <= x1 + TOL
    inside_y = y0 - TOL <= p[1] <= y1 + TOL
    near = min(abs(p[0] - x0), abs(p[0] - x1)) <= TOL and inside_y or \
           min(abs(p[1] - y0), abs(p[1] - y1)) <= TOL and inside_x
    return near

def inside(p, b):
    return b[0] < p[0] < b[2] and b[1] < p[1] < b[3]

errors, total, others = [], 0, 0
for svg_file in sorted(glob.glob("class-*.svg")):
    svg = open(svg_file, encoding="utf-8").read()
    src = open(svg_file.replace(".svg", ".puml"), encoding="utf-8").read()
    expected = len(re.findall(r'^\w+\s+<\|(?:--|\.\.)\s+\w+', src, re.M))
    bx = boxes(svg)
    tris = list(triangles(svg))
    if len(tris) != expected:
        errors.append(f"{svg_file}: {len(tris)} triangles drawn, {expected} inheritance/realization lines in the source")
    for t in tris:
        total += 1
        tip, base = tip_and_base(t)
        if tip is None:
            errors.append(f"{svg_file}: malformed triangle {t}")
            continue
        hits = [n for n, b in bx.items() if on_border(tip, b)]
        good = [n for n in hits if not inside(base, bx[n])]
        if not good:
            where = f"tip {tip[0]:.0f},{tip[1]:.0f}"
            errors.append(f"{svg_file}: triangle at {where} does not point into a class box "
                          f"(touches {hits or 'nothing'})")

    # other line ends: open arrowheads (association, dependency) and diamonds
    # (aggregation, composition) must also touch a class box from outside
    for fill, p in re.findall(r'<polygon fill="(#333333|#FFFFFF|none)" points="([^"]+)"', svg):
        v = [tuple(map(float, xy)) for xy in zip(*[iter(p.split(","))] * 2)][:-1]
        if len(v) == 3:
            continue   # hollow triangle, checked above
        c = (sum(x for x, _ in v) / len(v), sum(y for _, y in v) / len(v))
        others += 1
        if not any(on_border(pt, b) and not inside(c, b) for pt in v for b in bx.values()):
            errors.append(f"{svg_file}: line end near {c[0]:.0f},{c[1]:.0f} does not touch a class box")

print(f"checked {total} inheritance/realization arrowheads and {others} other line ends "
      f"in {len(glob.glob('class-*.svg'))} class diagrams")
print("\n".join(errors) if errors else
      "OK: every triangle touches the parent/interface box and points into it; every other line end touches its box")
sys.exit(1 if errors else 0)
