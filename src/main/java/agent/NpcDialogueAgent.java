package agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import engine.commands.ActionProposal;
import engine.commands.CommandFactory;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Item;
import engine.world.NPC;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Speaks for the NPC the player is talking to (F03, SD07): the model answers in character as
 * {@code {"text":"...","sideEffect":null}}, where the side effect is at most one game action, such as
 * handing over an item ({@code {"type":"take","target":"iron key"}}) or ending the conversation
 * ({@code {"type":"leave"}}).
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern. The prompt holds the NPC's
 * name, role, personality and knowledge (never its secrets), the items it owns here, the game state,
 * the story memory focused on this NPC and the last {@value #MAX_RECENT_DIALOGUE} dialogue events
 * with this NPC; the player's message is capped at {@value #MAX_MESSAGE_CHARS} characters and the
 * reply at {@value #MAX_REPLY_CHARS}.
 *
 * <p>"The LLM speaks, the rules decide": the text is only shown, and nothing in it is ever read as an
 * action. {@link #parse} rejects a side effect whose type is not an action type of the game;
 * {@link #isValid} rejects an empty text, a text containing one of the NPC's secrets, and a side
 * effect the command factory could not build. Whether a valid side effect happens is still decided
 * by the rules engine. The fallback is a pre-written line for the NPC's role (UC03 4b), without a
 * side effect.
 */
public class NpcDialogueAgent extends LlmTask<String, NpcReply> {

    /** How many of the latest dialogue events with this NPC the prompt repeats. */
    static final int MAX_RECENT_DIALOGUE = 6;
    /** Longest reply shown; a longer one is cut at a sentence or word boundary. */
    static final int MAX_REPLY_CHARS = 400;
    /** Longest part of the player's message that reaches the prompt and the event log. */
    static final int MAX_MESSAGE_CHARS = 300;
    private static final int MAX_TOKENS = 200;

    private static final Set<String> TEXT_FIELDS = Set.of("text", "reply", "say");
    private static final Set<String> SIDE_EFFECT_FIELDS = Set.of("sideeffect", "effect", "action");

    /** The pre-written lines per role, used in turn when the model gives no usable answer. */
    static final Map<String, List<String>> FALLBACK_LINES = Map.of(
            "blacksmith", List.of(
                    "Mind the sparks. What do you need?",
                    "Steel doesn't shape itself. Speak plainly or let me work.",
                    "If it's about the bandits: they took the relic to their camp, south of the Forest Edge."),
            "tavern keeper", List.of(
                    "Welcome in, friend. Pull up a stool.",
                    "Busy night. Ask what you need, I'm listening.",
                    "Folk say healing herbs still grow by the stream at the Old Mill."));
    private static final String DEFAULT_LINE = "nods, but says nothing more.";

    private final CommandFactory commandFactory = new CommandFactory();

    public NpcDialogueAgent(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /**
     * What the NPC the player is talking to says to the message.
     *
     * @throws IllegalStateException if the player is not in a conversation
     */
    public NpcReply reply(String message) {
        npc();
        return run(message == null ? "" : message);
    }

    @Override
    protected LlmRequest buildPrompt(String message) {
        NPC npc = npc();
        String system = prompts.fromTemplate("dialogue.system", Map.of(
                "name", npc.getName(),
                "role", npc.getRole(),
                "personality", npc.getPersonality(),
                "knowledge", npc.getKnowledge().stream().map(fact -> "- " + fact).collect(Collectors.joining("\n")),
                "ownedItems", ownedItemsHere(npc),
                "maxChars", String.valueOf(MAX_REPLY_CHARS)));
        String user = prompts.fromTemplate("dialogue.user", Map.of(
                "state", prompts.stateSummary(state),
                "memory", prompts.memoryContext(npc.getId(), PromptBuilder.MAX_MEMORY_CHARS),
                "recent", recentDialogue(npc),
                "player", state.getPlayer().getName(),
                "message", capMessage(message)));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /**
     * {@code null} if the reply holds no JSON object with a text, or names a side-effect type the game
     * does not have. A missing or null side effect, or one without a type or with type {@code "none"},
     * means none. Field names are matched
     * ignoring case and underscores ({@code text}/{@code reply}/{@code say},
     * {@code sideEffect}/{@code effect}/{@code action}); the text is cut to {@value #MAX_REPLY_CHARS}
     * characters.
     */
    @Override
    protected NpcReply parse(LlmResponse response) {
        JsonElement root = parser.parseJson(response.getText(), JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        JsonObject object = root.getAsJsonObject();
        String text = ActionInterpreter.field(object, TEXT_FIELDS);
        if (text == null || text.isBlank()) {
            return null;
        }
        ActionProposal sideEffect = null;
        JsonElement effect = element(object, SIDE_EFFECT_FIELDS);
        if (effect != null && !effect.isJsonNull() && !isNone(effect)) {
            if (!effect.isJsonObject()) {
                return null;
            }
            sideEffect = ActionInterpreter.proposal(effect.getAsJsonObject());
            if (sideEffect == null) {
                return null;
            }
        }
        return new NpcReply(truncate(text.strip(), MAX_REPLY_CHARS), sideEffect);
    }

    @Override
    protected boolean isValid(NpcReply output, String message) {
        return problem(output) == null;
    }

    @Override
    protected String describeInvalid(NpcReply output, String message) {
        String problem = problem(output);
        return problem == null ? super.describeInvalid(output, message) : problem;
    }

    /** The next pre-written line for the NPC's role, picked by how many dialogue events the NPC has had. */
    @Override
    protected NpcReply fallback(String message) {
        NPC npc = npc();
        List<String> lines = FALLBACK_LINES.get(npc.getRole().toLowerCase(Locale.ROOT));
        if (lines == null) {
            return new NpcReply(npc.getName() + " " + DEFAULT_LINE, null);
        }
        return new NpcReply(lines.get(dialogueEvents(npc).size() % lines.size()), null);
    }

    /** The first rule the reply breaks, or {@code null} if it breaks none. */
    private String problem(NpcReply reply) {
        String text = reply.getText().toLowerCase(Locale.ROOT);
        for (String secret : npc().getSecrets()) {
            if (text.contains(secret.toLowerCase(Locale.ROOT))) {
                return "the reply reveals a secret";
            }
        }
        if (reply.getSideEffect() != null) {
            try {
                commandFactory.create(reply.getSideEffect());
            } catch (IllegalArgumentException malformed) {
                return "the side effect is malformed (" + malformed.getMessage() + ")";
            }
        }
        return null;
    }

    private NPC npc() {
        if (!(state.getMode() instanceof DialogueMode dialogue)) {
            throw new IllegalStateException("The player is not talking to anyone");
        }
        return dialogue.getNpc();
    }

    private String ownedItemsHere(NPC npc) {
        String owned = state.currentLocation().getItems().stream()
                .filter(item -> npc.getId().equals(item.getOwnerId()))
                .map(Item::getName)
                .collect(Collectors.joining(", "));
        return owned.isEmpty() ? "none" : owned;
    }

    private List<GameEvent> dialogueEvents(NPC npc) {
        return state.getEventLog().filter(EventType.DIALOGUE).stream()
                .filter(event -> npc.getId().equals(event.getSubjectId()))
                .toList();
    }

    private String recentDialogue(NPC npc) {
        List<GameEvent> events = dialogueEvents(npc);
        List<GameEvent> recent = events.subList(Math.max(0, events.size() - MAX_RECENT_DIALOGUE), events.size());
        return recent.isEmpty() ? "(none yet)"
                : recent.stream().map(GameEvent::getDescription).collect(Collectors.joining("\n"));
    }

    /**
     * The player's message, trimmed and cut to {@value #MAX_MESSAGE_CHARS} characters, as it reaches
     * the prompt and the dialogue event.
     */
    public static String capMessage(String message) {
        return truncate(message.strip(), MAX_MESSAGE_CHARS);
    }

    /**
     * The text if it fits, otherwise cut after the last sentence end (or, failing that, the last word)
     * within the limit, with "..." added when it was cut mid-sentence.
     */
    static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String head = text.substring(0, maxChars - 3);
        int sentenceEnd = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        if (sentenceEnd >= maxChars / 2) {
            return head.substring(0, sentenceEnd + 1);
        }
        int space = head.lastIndexOf(' ');
        return (space > 0 ? head.substring(0, space) : head).stripTrailing() + "...";
    }

    private static JsonElement element(JsonObject object, Set<String> names) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (names.contains(ActionInterpreter.normalize(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean isNone(JsonElement effect) {
        if (effect.isJsonPrimitive()) {
            String value = effect.getAsString().trim();
            return value.isEmpty() || value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null");
        }
        if (effect.isJsonObject()) {
            String type = ActionInterpreter.field(effect.getAsJsonObject(), ActionInterpreter.TYPE_FIELDS);
            return type == null || type.isBlank() || type.trim().equalsIgnoreCase("none");
        }
        return false;
    }
}
