# AI Game Master (EECS 3311, Stage 2)

A text adventure for EECS 3311 (York University) in which a language model acts as the game master. The rule of the design is **"the LLM proposes, the engine decides"**: the model reads the player's words, voices the NPCs, offers side quests, keeps the story memory, writes the backstory and the epilogue, and narrates each turn, but a deterministic rules engine checks and executes every action that changes the game. Every AI feature has an offline fallback (a keyword parser, pre-written lines or a template), so the game is fully playable without a key or a network. It runs in a terminal (CLI) and in a Swing window (GUI) that share one controller.

**Author:** Hossein Gorji (221396551)

| Stage | Description                          | Status      |
| ----- | ------------------------------------ | ----------- |
| 1     | Design (UML, patterns, traceability) | Done        |
| 2     | Implementation                       | Implemented |
| 3     | Testing and validation               | Planned     |

The Stage 1 design is in [`docs/`](docs/): the [design report (PDF)](docs/AI-Game-Master-Stage1-Design-Report.pdf) and the UMLet diagrams in [`docs/diagrams/`](docs/diagrams/).

## 1. Requirements

- Java 17 or newer (`java -version`).
- Maven is not needed: the Maven Wrapper `./mvnw` is included and downloads Maven on first use.
- `mvnw.cmd` (Windows) is included but untested. The commands below are for macOS and Linux.

Run every command from the repository root: the game reads `.env` and writes `saves/` in the current directory.

## 2. Setup

```bash
cp .env.example .env
```

Then edit `.env` on your machine and replace the placeholder of the provider you use with your own key:

```
LLM_PROVIDER=deepseek
DEEPSEEK_API_KEY=<your DeepSeek key>
GROQ_API_KEY=<your Groq key>
```

- **DeepSeek is the default provider** (model `deepseek-flash`). For **Groq**, set `LLM_PROVIDER=groq` and `GROQ_API_KEY` (model `openai/gpt-oss-20b`).
- `LLM_API_KEY` is used for either provider when its own key is not set.
- `DEEPSEEK_MODEL` and `GROQ_MODEL` override the model, for example `GROQ_MODEL=openai/gpt-oss-120b`. Left blank or commented out, the default is used. An unknown model makes each call fail with `HTTP 404 (model <model> not found; set GROQ_MODEL)`.
- A value left as `YOUR_LOCAL_API_KEY_HERE` (the placeholder in `.env.example`) counts as not set.
- **Real environment variables override `.env`.** If `GROQ_API_KEY` or `DEEPSEEK_API_KEY` is exported in your shell (for example from an old session or your shell profile), that value is used instead of the one in `.env`. If you see `HTTP 401 (API key rejected)`, check for one and remove it, for example `unset GROQ_API_KEY`.
- **Without any key the game still runs**, in offline mode: typed commands go through a keyword parser and every AI feature uses its deterministic fallback. The game prints which key is missing at start-up.

## 3. Running

The CLI (the default):

```bash
./mvnw -q compile exec:java                          # provider from .env (LLM_PROVIDER, else deepseek)
./mvnw -q compile exec:java -Dexec.args=deepseek     # DeepSeek for this run
./mvnw -q compile exec:java -Dexec.args=groq         # Groq for this run
./mvnw -q compile exec:java -Dexec.args=offline      # no model at all
```

The GUI: the first argument is `gui`, optionally followed by the provider.

```bash
./mvnw -q compile exec:java -Dexec.args=gui
./mvnw -q compile exec:java -Dexec.args="gui deepseek"
./mvnw -q compile exec:java -Dexec.args="gui groq"
./mvnw -q compile exec:java -Dexec.args="gui offline"
```

There is no `cli` argument: any first word other than `gui` is read as the provider, and an unknown provider plays offline. Without a display, `gui` prints a message and starts nothing; use the CLI instead. The window title shows the provider and model in use, or `offline`.

## 4. Playing

Character creation: the CLI asks for a name and a class (`warrior`, `rogue` or `mage`, with preset stats 5/3/2, 2/6/2 and 2/3/5). The GUI has a name field, a class box and stat sliders (10 points, each stat 1 to 8). Then a backstory is shown. Your quest is to recover the village relic from the bandit leader.

| What                    | CLI                                                                  | GUI                                                                   |
| ----------------------- | -------------------------------------------------------------------- | --------------------------------------------------------------------- |
| Any action              | type it, e.g. `go north`, `take shed key and go south`               | type it in the field under the story panel                            |
| Move                    | `go north`, `go to the tavern`                                       | click a neighbouring place on the map                                 |
| Items                   | `take`, `drop`, `use`, `equip`, `put bread in pouch`, `inventory`    | Take (items here), Use, Equip, Drop (inventory tree)                  |
| Look around             | `look`                                                               | the story panel shows each place on arrival                           |
| Backstory               | `regenerate` (or `again`) for another; Enter (or `start`) to begin   | Generate Backstory, then Regenerate or Start                          |
| Talk                    | `talk to Marta`                                                      | Talk to <name> buttons under the map                                  |
| In a conversation       | everything you type is said to the person; `leave`, `bye`, `goodbye` end it | Say field and Leave in the dialogue panel                     |
| Quest offer             | `accept` or `decline`                                                | Accept or Decline on the quest card (typing also works)               |
| Fight                   | `attack wolf`                                                        | type it; then Attack in the combat panel                              |
| Flee                    | `flee`                                                               | Flee in the combat panel                                              |
| Save                    | `save <name>` (asks yes/no before overwriting; not during combat)    | Game > Save...                                                        |
| Load                    | `load <name>`; `saves` lists the saves                               | Game > Load..., or Load Game... on the start and end screens          |
| New game                | `new` (only after the ending)                                        | Game > New Game, or New Game on the end screen                        |
| Map and quests          | `map`, `quests`                                                      | the Map panel and the Quests tab                                      |
| Journal (story so far)  | `journal`                                                            | the Journal tab                                                       |
| Event history           | `history`, `history combat` (also `movement`, `item`, `dialogue`, `quest`, `rejected`) | the Event log tab with its filter                   |
| Help and quit           | `help`, `quit` (or `exit`)                                           | Game > Quit                                                           |

Saves are JSON files in `saves/<name>.json` in the current directory. A save name is 1 to 32 letters, digits, `-` or `_`, stored in lower case. The save format is version 2; a save in another format is refused with a message and the current game is left as it was. The game ends when the main quest is completed or the player dies; the end screen shows the ending type, the epilogue and statistics.

## 5. Features

| ID  | Feature                        | What it does                                                                     | Main classes                                         |
| --- | ------------------------------ | -------------------------------------------------------------------------------- | ---------------------------------------------------- |
| F01 | Character creation             | Name, class and stats; AI backstory with Regenerate, class template offline      | `CharacterFactory`, `BackstoryWriter`                |
| F02 | Natural-language actions       | Typed text becomes actions; the engine checks them; keyword parser offline       | `ActionInterpreter`, `OfflineFallbackParser`, `RulesEngine` |
| F03 | NPC dialogue                   | NPCs answer in character; secrets are kept; side effects go through the engine   | `NpcDialogueAgent`, `DialogueMode`                   |
| F04 | Combat and narration           | d20 plus stats decide every attack and flee; the AI narrates the real results    | `AttackCommand`, `FleeCommand`, `Narrator`           |
| F05 | Inventory                      | Nested containers, a 10 kg weight limit, take, drop, put, use, equip             | `Inventory`, `Container`                             |
| F06 | World navigation and map       | Eight locations in two regions, a locked door, a map of visited places           | `World`, `MoveCommand`, `MapPanel`                   |
| F07 | AI quest generation            | Side quests built only from real things, validated, template quest as fallback   | `QuestGenerator`, `QuestValidator`                   |
| F08 | Quest tracking                 | Events advance objectives; rewards are granted on completion                     | `QuestTracker`, `QuestLog`                           |
| F09 | Story memory and journal       | Key facts kept, older events summarized, memory added to prompts                 | `MemoryManager`, `StoryMemory`, `MemorySummarizer`   |
| F10 | Save and load                  | Full game state as versioned JSON, not during combat                             | `GameSnapshot`, `SaveManager`                        |
| F11 | Event log and history          | Every event with turn and time, filterable                                       | `EventLog`, `GameEventBus`                           |
| F12 | AI ending                      | Ending type from the player's choices; AI epilogue within that type              | `EndingCalculator`, `EndingWriter`                   |

## 6. Design patterns

| Pattern               | Classes                                                                                                   |
| --------------------- | --------------------------------------------------------------------------------------------------------- |
| Factory Method        | `CharacterFactory` with `WarriorFactory`, `RogueFactory`, `MageFactory`                                   |
| Command               | `GameCommand` and its commands (`MoveCommand`, `TakeCommand`, `AttackCommand`, ...); `CommandFactory` builds them, `RulesEngine` runs them |
| Composite             | `Item` (component), `Container` (composite), `Weapon`, `Consumable`, `KeyItem` (leaves); `Inventory` is the client |
| Observer              | `GameEventBus` (subject), `GameEventListener`; listeners `EventLog`, `QuestTracker`, `MemoryManager`, `CliGameView`, `SwingGameView` |
| State                 | `GameMode` with `ExplorationMode`, `CombatMode`, `DialogueMode`; `GameState` is the context              |
| Template Method       | `LlmTask` with `ActionInterpreter`, `Narrator`, `NpcDialogueAgent`, `QuestGenerator`, `MemorySummarizer`, `BackstoryWriter`, `EndingWriter` |
| Strategy              | `LLMClient` with `DeepSeekClient`, `GroqClient`, `ScriptedLLMClient` (offline)                            |
| Adapter               | `OpenAiCompatibleClient` adapts the JDK's `HttpClient` to `LLMClient`                                     |
| Facade, MVC           | `GameController` (controller and facade), `GameState` (model), `GameView`, `CliGameView`, `SwingGameView` (views) |
| Memento               | `GameSnapshot` (memento), `GameState` (originator), `SaveManager` (caretaker)                             |

## 7. Tests

```bash
./mvnw test        # or ./mvnw -q test for quiet output
```

The tests use a scripted client and fixed dice, so they need no network and no key. The two live provider checks in `LiveProviderSmokeTest` are skipped unless `LLM_LIVE_TEST=true` and a key are set, and then they call the real provider.

The Swing tests need a display (they keep the game window hidden; one shows the Load dialog briefly). On a machine without a display, or with

```bash
./mvnw test -DargLine=-Djava.awt.headless=true
```

they are skipped (not failed) and every other test runs.

## 8. Debugging

```bash
LLM_DEBUG=true ./mvnw -q compile exec:java
```

(or `LLM_DEBUG=true` in `.env`). Every failed model attempt is then described on standard error: the task, the attempt, the reason (for example `invalid output`, `LLM unavailable: timeout`, `LLM unavailable: HTTP 401 (API key rejected)`), the name of the rule a reply broke (for example `OBJECTIVE_TYPE`, `MAIN_QUEST`, `HIT_MISS`) and the model's raw reply. Requests, headers and keys are never printed. A call rejected with HTTP 401 or 403 is not retried.

Without debug output, a fallback is still reported in one line in the game, for example `(Basic-command mode: simple commands only. Reason: LLM unavailable.)`.

## 9. Known limitations

- The dice state is not saved, so the rolls after loading a save differ from the rolls the original game would have made.
- NPC secrets are stored in the save files in plain text, so anyone who opens a save can read them.
- NPC dialogue is only checked for revealed secrets and for well-formed side effects, not against the world's facts.
- The Narrator rejects invented capitalized names but does not catch invented lowercase nouns (such as "a golden amulet").
- Story-memory summaries are generated synchronously, during the turn that triggers them.
- DeepSeek has not been confirmed with a live call in this project; only Groq has.
- `mvnw.cmd` (Windows) is untested.

## 10. Security

- `.env` is ignored by git (`.gitignore`); `.env.example` is committed and holds only placeholders.
- API keys belong only in your local `.env` (or your environment). Never put a key in `.env.example`, in code or in any other tracked file.
- The game never prints a key: not at start-up, not in notices, not in `LLM_DEBUG` output, not in the GUI.

## Course

EECS 3311 Software Design, York University.
