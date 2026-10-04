"""Generate the class-diagram views (class-0 ... class-6) from model.iuml.

model.iuml is the single source of truth. Each class carries $tags naming the
views it appears in; the first tag after $ov is the class's "home" view, where
its attributes and operations are shown in full. In every other view the class
is drawn as a name-only box. A view shows a relationship only if both ends are
in the view and at least one end is at home there (the overview shows all
relationships among its classes), so cross-view links do not clutter a view.

Usage:  python3 gen_views.py   then render the class-*.puml files with PlantUML.
"""
import re

lines = open("model.iuml", encoding="utf-8").read().splitlines()

DECL = re.compile(r'\s*(abstract class|class|interface|enum)\s+(?:"[^"]+"\s+as\s+)?(\w+)([^{]*)(\{?)\s*$')
REL = re.compile(r'^(\w+)\s+(?:"[^"]+"\s+)?(\S+)\s+(?:"[^"]+"\s+)?(\w+)')

classes, order, pkg_of, rels = {}, [], {}, []
pkg, i = None, 0
while i < len(lines):
    line = lines[i]
    pm = re.match(r'package\s+"([^"]+)"\s*\{', line)
    if pm:
        pkg = pm.group(1); i += 1; continue
    if line.startswith("}"):
        pkg = None; i += 1; continue
    m = DECL.match(line)
    if m and pkg:
        name, rest, brace = m.group(2), m.group(3), m.group(4)
        tags = re.findall(r'\$(\w+)', rest)
        decl = re.sub(r'\s*\$\w+', '', line.rstrip().rstrip('{').rstrip())
        body = []
        if brace:
            i += 1
            while not re.match(r'\s*\}\s*$', lines[i]):
                body.append(lines[i]); i += 1
        classes[name] = {"decl": decl, "body": body, "tags": tags}
        order.append(name)
        pkg_of[name] = pkg
        i += 1; continue
    if pkg is None and REL.match(line) and not line.lstrip().startswith("'"):
        a, arrow, b = REL.match(line).groups()
        if a in classes and b in classes:
            rels.append((a, b, line))
    i += 1

def home(name):
    t = [x for x in classes[name]["tags"] if x != "ov"]
    return t[0] if t else "ov"

VIEWS = [
    ("class-0-overview", "Class Diagram 0 — Architecture Overview", "ov"),
    ("class-1-presentation", "Class Diagram 1 — Presentation and Control (MVC, Facade)", "ui"),
    ("class-2-engine-core", "Class Diagram 2 — Engine Core (State)", "core"),
    ("class-3-commands", "Class Diagram 3 — Game Commands (Command)", "cmd"),
    ("class-4-domain", "Class Diagram 4 — Domain Model (Factory Method, Composite)", "dom"),
    ("class-5-quests-events", "Class Diagram 5 — Quests, Events and Persistence (Observer, Memento)", "qe"),
    ("class-6-agent-llm", "Class Diagram 6 — Agent and LLM Access (Template Method, Strategy, Adapter)", "ag"),
]

# Comments (folded notes attached by a dashed line), constraints and layout hints.
EXTRAS = {
    "ov": ['legend right\nBackbone classes and how the layers connect.\nAttributes, operations, multiplicities and all\nother classes are shown in views 1–6.\nendlegend',
           "GameController -[hidden]down- RulesEngine",
           "GameState -[hidden]down- Player",
           "ActionInterpreter -[hidden]down- LLMClient"],
    "ui": ['note left of GameController\nFacade over the engine and agent layers:\nthe single entry point used by both views.\nend note',
           'note bottom of CliGameView\nCLI command table used by dispatch(line):\n  map, quests  →  render(getState())\n  journal  →  getJournal()\n  history  →  getRecentHistory(20);  history <type>  →  getHistory(type)\n  save <name>, load <name>, saves  →  saveGame(), loadGame(), listSaves()\n  accept, decline  →  respondToQuest(quest, accept)\n  leave, flee  →  performAction(proposal)\n  any other line  →  sendDialogue(line) in dialogue mode,\n                          otherwise submitCommand(line)\nend note',
           "GameState -[hidden]down- Narrator",
           "CommandFactory -[hidden]down- QuestGenerator",
           "SaveManager -[hidden]down- MemoryManager"],
    "core": ["CombatMode -[hidden]right- DialogueMode", "DialogueMode -[hidden]right- ExplorationMode", "GameMode -[hidden]down- DialogueMode", "RulesEngine -[hidden]left- DiceRoller", "StoryMemory -[hidden]right- DiceRoller"],
    "cmd": ["MoveCommand -[hidden]down- UseItemCommand", "UseItemCommand -[hidden]down- AttackCommand",
            "TakeCommand -[hidden]down- EquipCommand", "EquipCommand -[hidden]down- FleeCommand",
            "DropCommand -[hidden]down- TalkCommand", "TalkCommand -[hidden]down- LookCommand",
            "PutCommand -[hidden]down- LeaveCommand", "LeaveCommand -[hidden]down- InventoryCommand"],
    "dom": [],
    "qe": [],
    "ag": ['note top of LlmTask\nrun(input):  // template method, {leaf} = final\n  fallbackUsed = false\n  request = buildPrompt(input)\n  repeat up to MAX_ATTEMPTS times:\n    if llm.isAvailable():\n      response = llm.complete(request)\n      if response.isOk():\n        output = parse(response)\n        if output != null and isValid(output, input):\n          return output\n  fallbackUsed = true\n  return fallback(input)\nend note',
           'note "Type bindings of LlmTask<I, O>:\\nActionInterpreter <String, List<ActionProposal>>\\nNarrator <List<ActionResult>, String>\\nNpcDialogueAgent <String, NpcReply>\\nQuestGenerator <NPC, QuestProposal>\\nBackstoryWriter <Player, String>\\nMemorySummarizer <List<GameEvent>, String>\\nEndingWriter <Ending, String>" as NB',
           "NB .. LlmTask",
           "ActionInterpreter -[hidden]down- QuestGenerator",
           "Narrator -[hidden]down- BackstoryWriter",
           "NpcDialogueAgent -[hidden]down- MemorySummarizer",
           "QuestValidator -[hidden]down- LLMClient",
           "QuestProposal -[hidden]down- ScriptedLLMClient"],
}

for fname, title, tag in VIEWS:
    shown = [n for n in order if tag in classes[n]["tags"]]
    out = ["@startuml " + fname, "!include style.iuml", "skinparam linetype ortho",
           "set separator none", "title " + title, ""]
    for p in dict.fromkeys(pkg_of[n] for n in shown):
        out.append(f'package "{p}" {{')
        for n in shown:
            if pkg_of[n] != p:
                continue
            c = classes[n]
            full = (tag != "ov") and home(n) == tag
            if full and c["body"]:
                out.append(c["decl"] + " {")
                out += c["body"]
                out.append("  }")
            else:
                out.append(c["decl"])
        out.append("}")
    out.append("")
    for a, b, line in rels:
        if a in shown and b in shown and (tag == "ov" or home(a) == tag or home(b) == tag):
            if tag == "ov":   # overview: structure only, no labels or multiplicities
                line = re.sub(r'\s+"[^"]*"', '', line)
                line = re.sub(r'\s+:\s+.*$', '', line)
            out.append(line)
    if tag != "ov" and any(home(n) != tag for n in shown):
        out.append("legend right\nClasses shown as a name only are detailed in another view.\nendlegend")
    out += EXTRAS.get(tag, [])
    out.append("@enduml")
    open(fname + ".puml", "w", encoding="utf-8").write("\n".join(out) + "\n")

# Complete model in one diagram (large; intended for the zoomable SVG).
full = ["@startuml class-full", "!include style.iuml", "skinparam linetype ortho", "set separator none",
        "title Complete Class Diagram — AI Game Master (all classes, attributes, operations and relationships)", ""]
for p in dict.fromkeys(pkg_of[n] for n in order):
    full.append(f'package "{p}" {{')
    for n in order:
        if pkg_of[n] == p:
            c = classes[n]
            full += ([c["decl"] + " {"] + c["body"] + ["  }"]) if c["body"] else [c["decl"]]
    full.append("}")
full += [line for _, _, line in rels] + ["@enduml"]
open("class-full.puml", "w", encoding="utf-8").write("\n".join(full) + "\n")

print(len(classes), "classifiers,", len(rels), "relationships")
for _, _, t in VIEWS:
    print(f"  {t}: {sum(1 for n in classes if t in classes[n]['tags'])} classifiers")
