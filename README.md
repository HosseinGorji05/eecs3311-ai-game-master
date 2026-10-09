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
- **LLM access:** DeepSeek as the primary provider, Groq (llama-3.3-70b-versatile) as an alternative, both behind one client interface.
- **Design patterns (11):** MVC, Command, State, Observer, Template Method, Strategy, Facade, Factory Method, Composite, Memento, Adapter.

## API keys

API keys go in a local `.env` file, which is git-ignored. Never commit keys.

## Course

EECS 3311 Software Design, York University.
