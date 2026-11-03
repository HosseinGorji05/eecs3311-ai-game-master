package presentation;

import agent.ActionInterpreter;
import agent.MemoryManager;
import agent.MemorySummarizer;
import agent.NpcDialogueAgent;
import agent.NpcReply;
import agent.PromptBuilder;
import agent.QuestGenerator;
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
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.events.GameEventListener;
import engine.quests.Quest;
import engine.quests.QuestTracker;
import engine.world.NPC;
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
 * The event log, the memory manager and the quest tracker are subscribed to the bus, in this order,
 * when the controller is created, so the story memory records an event before the quest update it
 * causes; views that are {@link GameEventListener}s are subscribed when added.
 *
 * <p>Story memory (F09, SD06): the memory manager records the events, keeps key facts and summarizes
 * older events with its own LLM task; the interpreter, the dialogue agent and the quest generator share
 * one prompt builder that adds the memory to their prompts. A new game starts with the memory manager's
 * starting facts, and {@link #getJournal} shows the memory without calling the model.
 *
 * <p>Dialogue (SD07): while the player talks to an NPC, the view sends each line to
 * {@link #sendDialogue}; the NPC's reply is shown as speech, its side effect (if any) runs through
 * the rules engine like any command, and the exchange is published as a DIALOGUE event. LEAVE ends
 * the conversation through {@link #performAction}.
 *
 * <p>Quest offers (SD07, F07): after a reply, if the NPC has not offered a quest in this conversation
 * and {@code QuestLog.canOfferFrom} allows it, the conversation is marked as offered and the quest
 * generator builds a quest, which the views show. The player answers with {@link #respondToQuest};
 * only the quest offered in the current conversation can be answered. Accepting adds it to the quest
 * log and publishes a QUEST event (verb ACCEPTED); declining discards it, and the NPC offers nothing
 * more until a later conversation.
 *
 * <p>Not yet included (they come with their features): narration by the LLM (the narration is the
 * results' messages), the backstory, saving and loading and the ending.
 * Until the ending exists, a turn is "game over" when the player has died.
 */
public class GameController {

    static final String BASIC_MODE_NOTICE = "Basic-command mode: simple commands only.";
    static final String NOT_UNDERSTOOD = "I didn't understand. Please rephrase.";
    static final String EXAMPLES = "Try: go north, take shed key, equip sword, inventory, talk to Marta, attack wolf, flee.";
    static final String NOT_TALKING = "You are not talking to anyone.";
    static final String GAME_OVER = "The game is over.";
    static final String NO_OFFER = "Nobody is offering you a quest right now.";
    static final String QUEST_LOG_FULL = "Your quest log is full.";

    private final GameState state = new GameState();
    private final List<GameView> views = new ArrayList<>();
    private final RulesEngine rules;
    private final CommandFactory commandFactory = new CommandFactory();
    private final GameEventBus eventBus = new GameEventBus();
    private final MemoryManager memory;
    private final ActionInterpreter interpreter;
    private final NpcDialogueAgent dialogueAgent;
    private final QuestGenerator questGenerator;
    private Player newPlayer;
    private Quest offeredQuest;
    private DialogueMode offerConversation;

    public GameController(LLMClient llm) {
        this(llm, new DiceRoller(System.nanoTime()));
    }

    /** Creates a controller with the given dice, so tests can fix every roll. */
    public GameController(LLMClient llm, DiceRoller dice) {
        Objects.requireNonNull(llm, "llm");
        this.rules = new RulesEngine(Objects.requireNonNull(dice, "dice"), eventBus);
        this.memory = new MemoryManager(state, new MemorySummarizer(llm, new PromptBuilder(), new ResponseParser(), state));
        PromptBuilder prompts = new PromptBuilder(memory);
        this.interpreter = new ActionInterpreter(llm, prompts, new ResponseParser(), state);
        this.dialogueAgent = new NpcDialogueAgent(llm, prompts, new ResponseParser(), state);
        this.questGenerator = new QuestGenerator(llm, prompts, new ResponseParser(), state);
        eventBus.subscribe(state.getEventLog());
        eventBus.subscribe(memory);
        eventBus.subscribe(new QuestTracker(state, eventBus));
    }

    /**
     * Where the interpreter, the dialogue agent, the quest generator and the memory summarizer describe failed
     * model attempts (the reason and the raw reply), e.g. {@code System.err}; {@code null} turns this off.
     */
    public void setLlmDebugOutput(PrintStream debug) {
        interpreter.setDebugOutput(debug);
        dialogueAgent.setDebugOutput(debug);
        questGenerator.setDebugOutput(debug);
        memory.setDebugOutput(debug);
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
     * Creates the world, places the new character in its start location, adds the starting facts to the
     * story memory and shows the game.
     *
     * @throws IllegalStateException if no character was created with {@link #newGame}
     */
    public void startGame() {
        if (newPlayer == null) {
            throw new IllegalStateException("Create a character with newGame() first");
        }
        state.start(newPlayer, World.createDefault());
        memory.addStartingFacts();
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

    /**
     * Says something to the NPC the player is talking to (SD07). The reply is shown as
     * {@code Name: "text"} (with a notice when it is a pre-written line); a side effect runs through
     * the rules engine, which may refuse it, and only an accepted one's message is shown (F03). The
     * exchange is published as a DIALOGUE event with the NPC's id as subject. Only a side effect uses
     * up a turn. Then the NPC may offer a quest (see the class comment), with a notice when it is a
     * template quest.
     *
     * @return the reply, or {@code null} if the player is not in a conversation (an error is shown)
     *         or the game is over
     */
    public NpcReply sendDialogue(String message) {
        requireStarted();
        if (isPlayerDead()) {
            error(GAME_OVER, true);
            return null;
        }
        if (!(state.getMode() instanceof DialogueMode dialogue)) {
            error(NOT_TALKING, false);
            return null;
        }
        NPC npc = dialogue.getNpc();
        int turn = state.getTurn();
        NpcReply reply = dialogueAgent.reply(message);
        if (dialogueAgent.isFallbackUsed()) {
            String notice = npc.getName() + " answers with a pre-written line. Reason: "
                    + dialogueAgent.getFallbackReason() + ".";
            views.forEach(view -> view.showNotice(notice));
        }
        List<ActionResult> effects = List.of();
        if (reply.getSideEffect() != null) {
            effects = rules.executeAll(List.of(commandFactory.create(reply.getSideEffect())), state);
        }
        String speech = npc.getName() + ": \"" + reply.getText() + "\"";
        views.forEach(view -> view.showNarration(speech));
        eventBus.publish(new GameEvent(EventType.DIALOGUE, turn, state.getPlayer().getName() + " said \""
                + NpcDialogueAgent.capMessage(message == null ? "" : message) + "\" to " + npc.getName() + "; "
                + npc.getName() + " replied \"" + reply.getText() + "\"", npc.getId(), 0));
        for (ActionResult effect : effects) {
            if (effect.isSuccess()) {
                views.forEach(view -> view.showNarration(effect.getMessage()));
            }
        }
        offerQuest(dialogue);
        return reply;
    }

    /**
     * Answers the quest offered in the current conversation (SD07). Accepting adds it to the quest log
     * and publishes a QUEST event; if the log has filled up meanwhile, an error is shown instead.
     * Declining discards the offer. Either way the offer is closed. Answering does not use up a turn.
     * An error is shown if this quest is not the one on offer, e.g. after the conversation ended.
     */
    public void respondToQuest(Quest quest, boolean accept) {
        requireStarted();
        if (isPlayerDead()) {
            error(GAME_OVER, true);
            return;
        }
        if (quest == null || quest != offeredQuest || state.getMode() != offerConversation) {
            error(NO_OFFER, false);
            return;
        }
        offeredQuest = null;
        offerConversation = null;
        if (!accept) {
            views.forEach(view -> view.showNarration("You decline \"" + quest.getTitle() + "\"."));
            return;
        }
        if (!state.getQuestLog().accept(quest)) {
            error(QUEST_LOG_FULL, false);
            return;
        }
        eventBus.publish(new GameEvent(EventType.QUEST, state.getTurn(), "Quest accepted: " + quest.getTitle(),
                quest.getId(), 0, EventVerb.ACCEPTED));
    }

    /**
     * The story so far (UC09, SD06): the fixed facts, the summaries of older events and the recent
     * events. Reading it changes nothing and never calls the model.
     */
    public String getJournal() {
        requireStarted();
        return memory.journalText();
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
     * Builds every command first, so a proposal the factory cannot build (a missing target) stops the
     * whole turn with a message before anything happens; then runs them all.
     */
    private TurnResult execute(List<ActionProposal> proposals) {
        List<GameCommand> commands = new ArrayList<>();
        for (ActionProposal proposal : proposals) {
            try {
                commands.add(commandFactory.create(proposal));
            } catch (IllegalArgumentException incomplete) {
                String action = proposal.getType().name().toLowerCase(Locale.ROOT);
                return error("Say what to " + action + ".", false);
            }
        }
        List<ActionResult> results = rules.executeAll(commands, state);
        String narration = results.stream().map(ActionResult::getMessage).collect(Collectors.joining("\n"));
        views.forEach(view -> view.showNarration(narration));
        return new TurnResult(narration, results, isPlayerDead());
    }

    /** SD07: offers a quest if the conversation is still going, nothing was offered yet and the NPC may offer one. */
    private void offerQuest(DialogueMode dialogue) {
        NPC npc = dialogue.getNpc();
        if (state.getMode() != dialogue || dialogue.isQuestOffered() || !state.getQuestLog().canOfferFrom(npc)) {
            return;
        }
        dialogue.setQuestOffered();
        Quest quest = questGenerator.generate(npc);
        if (questGenerator.isFallbackUsed() && quest != null) {
            String notice = npc.getName() + " offers a pre-written quest. Reason: "
                    + questGenerator.getFallbackReason() + ".";
            views.forEach(view -> view.showNotice(notice));
        }
        if (quest != null) {
            offeredQuest = quest;
            offerConversation = dialogue;
            views.forEach(view -> view.showQuestOffer(quest));
        }
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
