"""Consistency check between the class model and the sequence diagrams.

Also checks that every message passes as many arguments as the operation declares.

For every message  A -> B : op(...)  in sd*.puml, the operation `op` must be
declared in the class of lifeline B or in one of its superclasses/interfaces.
<<create>> messages must target a class that exists in the model.
Messages to/from actors and to external systems are skipped.
"""
import glob, re, sys

model = open("model.iuml", encoding="utf-8").read()
ops, parents, arity = {}, {}, {}

def count_args(a):
    """Number of comma-separated arguments, ignoring commas inside <>, (), [] and string literals."""
    a = re.sub(r'"[^"]*"', '""', a.strip())
    if not a:
        return 0
    depth, n = 0, 1
    for ch in a:
        if ch in "<([": depth += 1
        elif ch in ">)]": depth -= 1
        elif ch == "," and depth == 0: n += 1
    return n
cls = None
for line in model.splitlines():
    m = re.match(r'\s*(?:abstract class|class|interface|enum)\s+(?:"[^"]+"\s+as\s+)?(\w+)', line)
    if m:
        cls = m.group(1); ops.setdefault(cls, set()); parents.setdefault(cls, set()); continue
    if re.match(r'\s*\}\s*$', line):
        cls = None; continue
    if cls:
        om = re.match(r'\s*[+\-#~]\s*(?:\{\w+\}\s*)?(\w+)(?:<[^>]*>)?\((.*?)\)(?::|\s|$)', line)
        if om:
            ops[cls].add(om.group(1))
            arity.setdefault((cls, om.group(1)), set()).add(count_args(om.group(2)))
for a, arrow, b in re.findall(r'^(\w+)\s+(<\|--|<\|\.\.)\s+(\w+)', model, re.M):
    parents.setdefault(b, set()).add(a)

def all_arities(c, op, seen=None):
    seen = seen or set()
    if c in seen or c not in ops: return set()
    seen.add(c)
    r = set(arity.get((c, op), set()))
    for p in parents.get(c, ()):
        r |= all_arities(p, op, seen)
    return r

def all_ops(c, seen=None):
    seen = seen or set()
    if c in seen or c not in ops: return set()
    seen.add(c)
    r = set(ops[c])
    for p in parents.get(c, ()):
        r |= all_ops(p, seen)
    return r

errors, checked = [], 0
for f in sorted(glob.glob("sd*.puml")):
    src = open(f, encoding="utf-8").read()
    lifeline = {}
    for kind, label, alias, rest in re.findall(r'^(participant|boundary|control|actor)\s+"([^"]+)"\s+as\s+(\w+)(.*)$', src, re.M):
        if kind == "actor" or "external" in rest:
            lifeline[alias] = None
            continue
        name = label.split("\\n")[0].replace("__", "")
        cname = name.split(":")[-1].strip() if ":" in name else name.strip()
        lifeline[alias] = cname
        if cname not in ops:
            errors.append(f"{f}: lifeline '{label}' has unknown class '{cname}'")
    for line in src.splitlines():
        if '-->' in line:
            continue   # dashed reply (return value), not an operation call
        m = re.match(r'\s*\[?(\w*)\s*-+>+\s*(\w+)\s*(\*\*)?\s*:\s*(.*)$', line)
        if not m:
            continue
        sender, target, create, text = m.group(1), m.group(2), m.group(3), m.group(4)
        if target not in lifeline:
            continue
        if sender in lifeline and lifeline[sender] is None:
            continue   # user gestures from the actor are UI events, not operations
        c = lifeline[target]
        if c is None:
            continue
        if create:
            cm = re.search(r'<<create>>\s*(\w+)', text)
            if not cm or cm.group(1) != c:
                errors.append(f"{f}: create message '{text}' does not construct {c}")
            elif cm.group(1) not in all_ops(c):
                errors.append(f"{f}: constructor {c}() not declared in the class diagram")
            else:
                am = re.search(r'<<create>>\s*\w+\((.*)\)\s*$', text)
                if am and count_args(am.group(1)) not in all_arities(c, c):
                    errors.append(f"{f}: constructor {c}() called with {count_args(am.group(1))} argument(s)")
            checked += 1
            continue
        om = re.match(r'(\w+)\(', text)
        if not om:
            errors.append(f"{f}: message to {c} is not an operation call: '{text}'")
            continue
        checked += 1
        if om.group(1) not in all_ops(c):
            errors.append(f"{f}: {c} has no operation {om.group(1)}()")
            continue
        am = re.match(r'\w+\((.*)\)\s*$', text)
        n = count_args(am.group(1)) if am else None
        if n is not None and n not in all_arities(c, om.group(1)):
            errors.append(f"{f}: {c}.{om.group(1)}() called with {n} argument(s), declared with {sorted(all_arities(c, om.group(1)))}")

import os
newest_src = max(os.path.getmtime(f) for f in ["model.iuml", "gen_views.py", "style.iuml"])
for img in glob.glob("class-*.png") + glob.glob("class-*.svg"):
    if os.path.getmtime(img) < newest_src:
        errors.append(f"{img} is stale: older than model.iuml / gen_views.py; regenerate and re-render")
for src in glob.glob("sd*.puml"):
    for ext in (".png", ".svg"):
        img = src[:-5] + ext
        if not os.path.exists(img) or os.path.getmtime(img) < max(os.path.getmtime(src), os.path.getmtime("seq-style.iuml")):
            errors.append(f"{img} is stale or missing: re-render {src}")
print(f"checked {checked} messages in {len(glob.glob('sd*.puml'))} sequence diagrams")
print("\n".join(errors) if errors else "OK: every message is an operation of the receiving class")
sys.exit(1 if errors else 0)
