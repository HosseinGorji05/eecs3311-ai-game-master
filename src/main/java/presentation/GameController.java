package presentation;

import agent.ActionInterpreter;
import agent.PromptBuilder;
import agent.ResponseParser;
import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionResult;
import engine.commands.CommandFactory;
import engine.commands.GameCommand;
import engine.core.DiceRoller;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.events.GameEventListener;
import engine.quests.QuestTracker;
import engine.world.World;
import llm.LLMClient;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The single entry point the views use to play: it owns the game state, the event bus and the
 * engine, and runs each turn (SD02, SD03).
 *
 * <p>Pattern roles: <b>Controller</b> of MVC and <b>Facade</b> over the engine and agent layers.
 * The event log and the quest tracker are subscribed to the bus when the controller is created;
 * views that are {@link GameEventListener}s are subscribed when added.
 *
 * <p>Not yet included (they come with their features): narration by the LLM (the narration is the
 * results' messages), dialogue, quest offers, the backstory, saving and loading, the journal and the
 * ending. Until the ending exists, a turn is "game over" when the player has died.
 */
public class GameController {

    static final String BASIC_MODE_NOTICE = "Basic-command mode: simple commands only.";
    static final String NOT_UNDERSTOOD = "I didn't understand. Please rephrase.";
    static final String EXAMPLES = "Try: go north, take shed key, attack wolf, flee.";
    static final String GAME_OVER = "The game is over.";

    private final GameState state = new GameState();
    private final List<GameView> views = new ArrayList<>();
    private final RulesEngine rules;
    private final CommandFactory commandFactory = new CommandFactory();
    private final GameEventBus eventBus = new GameEventBus();
    private final ActionInterpreter interpreter;
    private Player newPlayer;

    public GameController(LLMClient llm) {
        this(llm, new DiceRoller(System.nanoTime()));
    }

    /** Creates a controller with the given dice, so tests can fix every roll. */
    public GameController(LLMClient llm, DiceRoller dice) {
        Objects.requireNonNull(llm, "llm");
        this.rules = new RulesEngine(Objects.requireNonNull(dice, "dice"), eventBus);
        this.interpreter = new ActionInterpreter(llm, new PromptBuilder(), new ResponseParser(), state);
        eventBus.subscribe(state.getEventLog());
        eventBus.subscribe(new QuestTracker(state, eventBus));
    }

    /**
     * Where the interpreter describes failed model attempts (its reason and the raw reply), e.g.
     * {@code System.err}; {@code null} turns this off.
     */
    public void setLlmDebugOutput(PrintStream debug) {
        interpreter.setDebugOutput(debug);
    }

    /** Adds a view; a view that listens to events is also subscribed to the bus. */
    public void addView(GameView view) {
        views.add(Objects.requireNonNull(view, "view"));
        if (view instanceof GameEventListener listener) {
            eventBus.subscribe(listener);
        }
    }

    public GameState getState() {
        return state;
    }

    /**
     * Creates the player character with its class's HP and starting kit (SD01).
     *
     * @throws IllegalArgumentException if the name is blank or the stats break the rules
     */
    public Player newGame(String name, CharacterClass cls, Stats stats) {
        newPlayer = CharacterFactory.forClass(cls).createCharacter(name, stats);
        return newPlayer;
    }

    /**
     * Creates the world, places the new character in its start location and shows it.
     *
     * @throws IllegalStateException if no character was created with {@link #newGame}
     */
    public void startGame() {
        if (newPlayer == null) {
            throw new IllegalStateException("Create a character with newGame() first");
        }
        state.start(newPlayer, World.createDefault());
        views.forEach(view -> view.render(state));
    }

    /**
     * Plays one turn from the player's typed text (SD02): the interpreter turns it into proposals
     * (with a basic-command notice naming the reason if it had to fall back), then the commands run
     * through the rules engine.
     */
    public TurnResult submitCommand(String text) {
        requireStarted();
        if (isPlayerDead()) {
            return error(GAME_OVER, true);
        }
        List<ActionProposal> proposals = interpreter.interpret(text);
        if (interpreter.isFallbackUsed()) {
            String notice = BASIC_MODE_NOTICE + " Reason: " + interpreter.getFallbackReason() + ".";
            views.forEach(view -> view.showNotice(notice));
        }
        if (proposals.isEmpty()) {
            return error(interpreter.isFallbackUsed() ? NOT_UNDERSTOOD + " " + EXAMPLES : NOT_UNDERSTOOD, false);
        }
        return execute(proposals);
    }

    /** Plays one turn from a button or a fixed CLI word, without the interpreter (SD10-SD12). */
    public TurnResult performAction(ActionProposal proposal) {
        requireStarted();
        Objects.requireNonNull(proposal, "proposal");
        if (isPlayerDead()) {
            return error(GAME_OVER, true);
        }
        return execute(List.of(proposal));
    }

    /** The recorded events of this type, oldest first. */
    public List<GameEvent> getHistory(EventType filter) {
        return state.getEventLog().filter(filter);
    }

    /** The last {@code count} recorded events, oldest first. */
    public List<GameEvent> getRecentHistory(int count) {
        return state.getEventLog().recent(count);
    }

    /**
     * Builds every command first, so an action type without a command yet stops the whole turn
     * with a friendly message before anything happens; then runs them all.
     */
    private TurnResult execute(List<ActionProposal> proposals) {
        List<GameCommand> commands = new ArrayList<>();
        for (ActionProposal proposal : proposals) {
            try {
                commands.add(commandFactory.create(proposal));
            } catch (IllegalArgumentException notAvailable) {
                String action = proposal.getType().name().toLowerCase(Locale.ROOT);
                return error("You can't " + action + " yet: that action is not available in this version.", false);
            }
        }
        List<ActionResult> results = rules.executeAll(commands, state);
        String narration = results.stream().map(ActionResult::getMessage).collect(Collectors.joining("\n"));
        views.forEach(view -> view.showNarration(narration));
        return new TurnResult(narration, results, isPlayerDead());
    }

    private TurnResult error(String message, boolean gameOver) {
        views.forEach(view -> view.showError(message));
        return new TurnResult(message, List.of(), gameOver);
    }

    private boolean isPlayerDead() {
        return !state.getPlayer().isAlive();
    }

    private void requireStarted() {
        if (state.getPlayer() == null) {
            throw new IllegalStateException("The game has not started");
        }
    }
}
