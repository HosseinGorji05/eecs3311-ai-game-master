# Diagrams (UMLet)

All UML diagrams of the Stage 1 report are drawn in [UMLet](https://www.umlet.com/). Each diagram is an editable UMLet file (`.uxf`) with a PNG and an SVG export next to it. To edit one, open the `.uxf` in UMLet (File > Open).

| Diagram | Files |
|---|---|
| Use-case diagram | `use-case-diagram` |
| Class diagram, View 0 architecture overview | `class-0-overview` |
| Class diagram, View 1 presentation and control | `class-1-presentation` |
| Class diagram, View 2 engine core | `class-2-engine-core` |
| Class diagram, View 3 game commands | `class-3-commands` |
| Class diagram, View 4 domain model | `class-4-domain` |
| Class diagram, View 5 quests, events and persistence | `class-5-quests-events` |
| Class diagram, View 6 agent and LLM access | `class-6-agent-llm` |
| SD00 Run an LLM task | `sd00-run-llm-task` |
| SD01 Create character | `sd01-create-character` |
| SD02 Perform action in natural language | `sd02-natural-language-turn` |
| SD03 Execute game commands | `sd03-execute-commands` |
| SD04 Fight enemy | `sd04-fight-enemy` |
| SD05 Propagate game events and track quests | `sd05-events-and-quests` |
| SD06 Story memory, journal and event history | `sd06-memory-journal-history` |
| SD07 Talk to NPC and accept quest | `sd07-talk-and-quest` |
| SD08 Save and load game | `sd08-save-load` |
| SD09 Reach ending | `sd09-reach-ending` |
| SD10 Take an item with an inventory button | `sd10-take-item` |
| SD11 Move to an adjacent location through a locked door | `sd11-move` |
| SD12 Flee from combat or leave a conversation | `sd12-flee-and-leave` |

Reading the sequence diagrams: notes are numbered (n1), (n2), ... and listed under the diagram title; the number is shown where the note applies. A **ref** frame points to another sequence diagram that is drawn once and reused. SD06 and SD08 contain one frame per scenario (for example Save and Load).

The SVG files are vector graphics: zoom in to read the small text of the large diagrams.
