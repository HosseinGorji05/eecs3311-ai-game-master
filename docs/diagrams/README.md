# Stage 1 UML diagrams

All diagrams in the Stage 1 report, with their sources. Every diagram has a `.png` (used in the report) and an `.svg` (zoomable).

| Diagram | Files | Source |
| --- | --- | --- |
| Use-case diagram (UC01–UC14) | `use-case-diagram.png/.svg` | `gen_usecase.py` |
| Class diagram, View 0 — Architecture overview | `class-0-overview.*` | `model.iuml` via `gen_views.py` |
| Class diagram, View 1 — Presentation and control | `class-1-presentation.*` | 〃 |
| Class diagram, View 2 — Engine core | `class-2-engine-core.*` | 〃 |
| Class diagram, View 3 — Game commands | `class-3-commands.*` | 〃 |
| Class diagram, View 4 — Domain model | `class-4-domain.*` | 〃 |
| Class diagram, View 5 — Quests, events and persistence | `class-5-quests-events.*` | 〃 |
| Class diagram, View 6 — Agent and LLM access | `class-6-agent-llm.*` | 〃 |
| Complete class diagram (all classes in one) | `class-full.*` | 〃 |
| Sequence diagrams SD00–SD12 | `sd00-*.puml` … `sd12-*.puml` (+ `.png/.svg`) | the `.puml` files |

## How the sources fit together

- `model.iuml` is the **single source of truth** for the class diagram: every class, attribute, operation and relationship. Each class is tagged with the views it appears in; `gen_views.py` generates `class-*.puml` from it.
- `check_consistency.py` verifies that every message in the sequence diagrams is an operation of the receiving object's class (or a superclass/interface) in `model.iuml`, called with the declared number of arguments, and that no rendered image is older than its source.
- `check_arrowheads.py` measures the rendered SVGs: every inheritance/realization line must start at the subclass or implementing class and end with its hollow triangle touching the superclass or interface box and pointing into it (EECS3311 UML I, slides 21 and 23). Every other line end (association arrowheads, aggregation and composition diamonds) must touch its class box.
- `convert_returns.py` rewrote every `return` in the sequence diagrams as an explicit UML reply (dashed line, open arrowhead), as in the UML II slides; it is kept for reference.
- `gen_usecase.py` checks its own layout: no line crosses a use case, another line or a label.
- `style.iuml` and `seq-style.iuml` hold the shared styling (`seq-style.iuml` uses PlantUML's strict UML style).

## Regenerating

```
python3 gen_views.py                      # class-diagram views from model.iuml
java -DPLANTUML_LIMIT_SIZE=16384 -jar plantuml.jar -tpng class-*.puml sd*.puml
java -DPLANTUML_LIMIT_SIZE=16384 -jar plantuml.jar -tsvg class-*.puml sd*.puml
python3 check_consistency.py              # must print "OK"
python3 check_arrowheads.py               # must print "OK"
python3 gen_usecase.py                    # use-case diagram (needs Playwright for the PNG)
```

To change the design, edit `model.iuml` (and the affected `sd*.puml`), then regenerate and re-run the check. The PlantUML extension for VS Code can preview any `.puml` file.

## The report PDF

`../AI-Game-Master-Stage1-Design-Report.pdf` is built by `../build_report_pdf.py` from the report's Markdown export (`../stage1-report.md`) and the SVG diagrams in this folder:

```
cd docs && python3 build_report_pdf.py stage1-report.md AI-Game-Master-Stage1-Design-Report.pdf
```

It needs Python with `markdown-it-py` and Playwright (Chromium). The diagrams go into the PDF as vector graphics, so they stay sharp at any zoom; the wide class views and the traceability table are on landscape pages.

