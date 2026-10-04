"""Rewrite `return x` lines in sd*.puml as explicit UML reply messages.

PlantUML's `return` draws a dashed line with a filled arrowhead. The UML II
slides draw a reply as a dashed line with an OPEN arrowhead, which PlantUML
writes as `callee -->> caller : x`. This script tracks the activation stack
(autoactivate is on: every synchronous call activates its receiver) and
replaces each `return x` with the matching explicit reply.

Usage: python3 convert_returns.py sd*.puml   (edits the files in place)
"""
import re, sys

CALL = re.compile(r'^\s*(\[|\w+)\s*->\s*(\w+)\s*(\*\*)?\s*:')
REPLY = re.compile(r'^\s*(\w+)\s*-->>?\s*(\w+|\[)')
OUT_REPLY = re.compile(r'^\s*\[<<?--\s*(\w+)')
RET = re.compile(r'^(\s*)return\b\s*(.*)$')

for f in sys.argv[1:]:
    out, stack = [], []
    for line in open(f, encoding="utf-8").read().splitlines():
        m = CALL.match(line)
        if m:
            if not m.group(3):               # «create» messages do not activate
                stack.append((m.group(1), m.group(2)))
            out.append(line)
            continue
        m = RET.match(line)
        if m:
            caller, callee = stack.pop()
            indent, text = m.groups()
            if caller == "[":
                out.append(f"{indent}[<<-- {callee}" + (f" : {text}" if text else ""))
            else:
                out.append(f"{indent}{callee} -->> {caller}" + (f" : {text}" if text else ""))
            continue
        if OUT_REPLY.match(line) or REPLY.match(line):
            stack.pop()
            line = re.sub(r'\[<--', '[<<--', line)
        out.append(line)
    if stack:
        print(f"{f}: activations left open at end: {stack}")
    open(f, "w", encoding="utf-8").write("\n".join(out) + "\n")
print("converted", len(sys.argv) - 1, "files")
