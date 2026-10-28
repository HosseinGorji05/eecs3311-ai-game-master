# AI Game Master

A text adventure for EECS 3311 (York University) in which an LLM acts as the game master. The design rule is **"the LLM proposes, the engine decides"**: the model interprets the player's words and narrates the story, but a deterministic rules engine validates and executes every action that changes the game state.

**Author:** Hossein Gorji (221396551)

## Status

| Stage | Description | Status |
|-------|-------------|--------|
| 1 | Design (UML, patterns, traceability) | Done |
| 2 | Implementation | Planned |
| 3 | Testing and validation | Planned |

## Stage 1 deliverables

- [Stage 1 Design Report (PDF)](docs/AI-Game-Master-Stage1-Design-Report.pdf): project overview, feature specifications, class diagram, design patterns, use cases, sequence diagrams, feature-to-design traceability, and feature implementation explanations.
- [`docs/diagrams/`](docs/diagrams/): all UML diagrams (use-case, seven class-diagram views, thirteen sequence diagrams), drawn in UMLet. Each one is an editable UMLet file (`.uxf`) with PNG and SVG exports.

## Design at a glance

- **Language and UI:** Java 17, Swing GUI plus a command-line interface, structured as MVC.
- **LLM access:** DeepSeek as the primary provider, Groq (openai/gpt-oss-20b) as an alternative, both behind one client interface.
- **Design patterns (11):** MVC, Command, State, Observer, Template Method, Strategy, Facade, Factory Method, Composite, Memento, Adapter.

## Build and test

Prerequisite: JDK 17 or newer (`java -version`). Maven does not need to be installed; the Maven Wrapper downloads Maven 3.9.16 on first use.

```bash
./mvnw -q test        # macOS / Linux
mvnw.cmd -q test      # Windows
```

## Configuration

The game uses DeepSeek (`deepseek-flash`) by default, or Groq (`openai/gpt-oss-20b`). To connect one:

1. Copy the template: `cp .env.example .env` (Windows: `copy .env.example .env`).
2. Enter your own key in `.env` on your machine: `DEEPSEEK_API_KEY` for DeepSeek, or `GROQ_API_KEY` together with `LLM_PROVIDER=groq` for Groq. `LLM_API_KEY` is used for either provider when its own key is empty.
3. Run the game:

```bash
./mvnw -q compile exec:java        # macOS / Linux
mvnw.cmd -q compile exec:java      # Windows
```

To use another model, set `DEEPSEEK_MODEL` or `GROQ_MODEL` to its model ID (for example `GROQ_MODEL=openai/gpt-oss-120b`); left blank or unset, the default above is used. Change it when a provider retires a model or your account cannot use it: an unknown model makes every turn fall back with `LLM unavailable: HTTP 404 (model <model> not found; set GROQ_MODEL)`.

Environment variables with the same names override `.env`. To pick a provider for one run, pass it as an argument, for example `./mvnw -q compile exec:java -Dexec.args=groq` (`deepseek`, `groq` or `offline`). Without a key, the game prints which key is missing and plays offline. When a turn falls back to basic-command mode, the notice gives the reason (for example `LLM unavailable: HTTP 401 (API key rejected)`, `LLM unavailable: timeout` or `invalid output`). Set `LLM_DEBUG=true` to print each failed attempt's reason and the model's raw reply to standard error; requests, headers and keys are never printed.

`.env` is git-ignored. Never commit it, and never put a key in `.env.example` or any other tracked file.

## Play (offline CLI)

No key is needed to play. Without one (or with `-Dexec.args=offline`) the game runs in basic-command mode, where a keyword parser reads simple commands:

```bash
./mvnw -q compile exec:java        # macOS / Linux
mvnw.cmd -q compile exec:java      # Windows
```

Type commands such as `go north`, `take shed key`, `attack wolf` or `flee`. `help` lists the rest and `quit` ends the game. With a provider connected, you can also type freer sentences such as `pick up the shed key and head north`. If the provider fails or times out, that turn falls back to basic-command mode.

## Course

EECS 3311 Software Design, York University.
