package agent;

import com.google.gson.JsonElement;
import engine.character.CharacterClass;
import engine.character.Player;
import engine.character.StatType;
import engine.character.Stats;
import engine.core.GameState;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a new character's backstory (F01, SD01): {@value #MIN_SENTENCES}-{@value #MAX_SENTENCES} sentences
 * that fit the name, class and stats.
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern ({@code LlmTask<Player, String>}).
 * The prompt holds only the player's name, class and stats (F01: "sends the name, class and stats"),
 * marked as data; never the story memory, the world, an NPC or anything from the configuration. The model
 * answers {@code {"backstory":"..."}} (a reply that is plain text with no JSON at all is read as the
 * backstory itself). {@link #parse} cleans the text ({@link #clean}): control and invisible characters go,
 * lines and paragraphs are kept, spaces are collapsed.
 *
 * <p>{@link #isValid} accepts a backstory only if it ({@link Rule}):
 * <ul>
 *   <li>is not empty ({@link Rule#EMPTY});</li>
 *   <li>has at most {@value #MAX_BACKSTORY_CHARS} characters ({@link Rule#TOO_LONG});</li>
 *   <li>has {@value #MIN_SENTENCES}-{@value #MAX_SENTENCES} sentences, each ending with ".", "!" or "?"
 *       ({@link Rule#SENTENCES});</li>
 *   <li>is plain prose: none of the characters {@code { } [ ] < > ` # * | \ _ ~}, no line that starts like a
 *       list item ({@link Rule#MARKUP});</li>
 *   <li>speaks to no one but the reader: no role label such as "System:" and nothing that tells the game
 *       or the model to ignore, change or override something ({@link Rule#INSTRUCTION}).</li>
 * </ul>
 *
 * <p>After one retry, or without a model, the fallback is the template for the class, filled with the
 * name. The backstory is flavour text only (F01): it is shown and saved, and nothing in the game ever reads
 * it, so whatever it claims never changes stats, HP, gold, XP or the inventory.
 */
public class BackstoryWriter extends LlmTask<Player, String> {

    /** Longest backstory (about five long sentences). */
    public static final int MAX_BACKSTORY_CHARS = 800;
    /** Fewest and most sentences (F01: "a 3-5 sentence backstory"). */
    public static final int MIN_SENTENCES = 3;
    public static final int MAX_SENTENCES = 5;

    private static final int MAX_TOKENS = 400;
    private static final Set<String> BACKSTORY_FIELDS = Set.of("backstory", "story", "text");
    private static final Pattern MARKUP = Pattern.compile("[{}\\[\\]<>`#*|\\\\_~]");
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^\\s*(?:[-+•]\\s|\\d+[.)]\\s)");
    private static final Pattern ROLE_LABEL = Pattern.compile("(?im)^\\s*(?:system|assistant|user|developer|game|note)\\s*:");
    private static final Pattern ORDER = Pattern.compile(
            "(?i)\\b(?:ignore|disregard|override|forget)\\b[^.!?]{0,40}\\b(?:instructions?|rules?|prompts?|previous|above)\\b");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]+[\"')”’]*(?=\\s|$)");

    /** The rules a backstory from the model must keep, named in the debug output. */
    enum Rule {
        EMPTY,
        TOO_LONG,
        SENTENCES,
        MARKUP,
        INSTRUCTION
    }

    /** The template per class; {@code %s} is the name. */
    static final Map<CharacterClass, String> TEMPLATES = Map.of(
            CharacterClass.WARRIOR, "%s grew up hauling iron in a border village and learned to fight before learning"
                    + " to read. Years as a caravan guard taught %s that a steady arm matters more than a loud boast."
                    + " When word came that bandits had stolen the village relic, %s set out to bring it back.",
            CharacterClass.ROGUE, "%s learned early that locks and pockets open for those who are patient. A few close"
                    + " calls in the city taught %s to move quietly and to trust nobody's word too much. Rumours of a"
                    + " stolen relic and the bandits who took it have now brought %s to the village.",
            CharacterClass.MAGE, "%s spent long years copying spells by candlelight under a strict old teacher. The"
                    + " teacher's last lesson was that knowledge is worthless unless it helps someone. Hearing that"
                    + " bandits had stolen the village relic, %s closed the books and took the road to the village.");

    public BackstoryWriter(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /** The backstory for the new character: the model's if it gives a valid one, else the class template. Never fails. */
    public String write(Player player) {
        return run(Objects.requireNonNull(player, "player"));
    }

    @Override
    protected LlmRequest buildPrompt(Player player) {
        Stats stats = player.getStats();
        String system = prompts.fromTemplate("backstory.system", Map.of(
                "minSentences", String.valueOf(MIN_SENTENCES),
                "maxSentences", String.valueOf(MAX_SENTENCES),
                "maxChars", String.valueOf(MAX_BACKSTORY_CHARS)));
        String user = prompts.fromTemplate("backstory.user", Map.of(
                "name", player.getName(),
                "class", player.getCharacterClass().name().toLowerCase(Locale.ROOT),
                "strength", String.valueOf(stats.modifier(StatType.STRENGTH)),
                "agility", String.valueOf(stats.modifier(StatType.AGILITY)),
                "intelligence", String.valueOf(stats.modifier(StatType.INTELLIGENCE))));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /**
     * The cleaned backstory from {@code {"backstory":"..."}} ({@code story} and {@code text} are read too), or
     * from a reply with no JSON at all; {@code null} if a JSON reply has no such text.
     */
    @Override
    protected String parse(LlmResponse response) {
        String text = response.getText();
        if (text == null) {
            return null;
        }
        if (text.indexOf('{') < 0) {
            return clean(text);
        }
        JsonElement root = parser.parseJson(text, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        String backstory = ActionInterpreter.field(root.getAsJsonObject(), BACKSTORY_FIELDS);
        return backstory == null ? null : clean(backstory);
    }

    @Override
    protected boolean isValid(String backstory, Player player) {
        return problem(backstory) == null;
    }

    @Override
    protected String describeInvalid(String backstory, Player player) {
        String problem = problem(backstory);
        return problem == null ? super.describeInvalid(backstory, player) : problem;
    }

    /** The template for the player's class, filled with the name. */
    @Override
    protected String fallback(Player player) {
        return template(player.getCharacterClass(), player.getName());
    }

    static String template(CharacterClass cls, String name) {
        return TEMPLATES.get(cls).replace("%s", name);
    }

    /** The first rule the backstory breaks, as "RULE: detail", or {@code null}. */
    static String problem(String backstory) {
        if (backstory == null || backstory.isBlank()) {
            return Rule.EMPTY + ": the backstory is empty";
        }
        if (backstory.length() > MAX_BACKSTORY_CHARS) {
            return Rule.TOO_LONG + ": " + backstory.length() + " characters, at most " + MAX_BACKSTORY_CHARS;
        }
        Matcher markup = MARKUP.matcher(backstory);
        if (markup.find()) {
            return Rule.MARKUP + ": it contains \"" + markup.group() + "\"";
        }
        if (LIST_ITEM.matcher(backstory).find()) {
            return Rule.MARKUP + ": it contains a list";
        }
        if (ROLE_LABEL.matcher(backstory).find() || ORDER.matcher(backstory).find()) {
            return Rule.INSTRUCTION + ": it gives an instruction instead of telling a story";
        }
        int sentences = sentenceCount(backstory);
        char last = backstory.charAt(backstory.length() - 1);
        boolean ended = SENTENCE_END.matcher(backstory).find() && ".!?\"')”’".indexOf(last) >= 0;
        if (sentences < MIN_SENTENCES || sentences > MAX_SENTENCES || !ended) {
            return Rule.SENTENCES + ": " + sentences + " sentences" + (ended ? "" : ", the last one unfinished")
                    + ", it needs " + MIN_SENTENCES + "-" + MAX_SENTENCES;
        }
        return null;
    }

    /** How many sentence ends ("." "!" "?" followed by a space or the end) the text has, plus an unfinished last one. */
    static int sentenceCount(String text) {
        Matcher end = SENTENCE_END.matcher(text);
        int count = 0;
        int afterLast = 0;
        while (end.find()) {
            if (!text.substring(afterLast, end.start()).isBlank()) {
                count++;
            }
            afterLast = end.end();
        }
        return text.substring(afterLast).isBlank() ? count : count + 1;
    }

    /**
     * The text as it may be stored: line breaks unified; invisible format characters (zero-width, direction
     * marks) removed; control characters and runs of spaces inside a line turned into one space; lines kept,
     * and paragraphs kept with one blank line between them.
     */
    static String clean(String text) {
        StringBuilder cleaned = new StringBuilder();
        boolean blankBefore = false;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String kept = line.replaceAll("\\p{Cf}", "").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
            if (kept.isEmpty()) {
                blankBefore = cleaned.length() > 0;
                continue;
            }
            if (cleaned.length() > 0) {
                cleaned.append(blankBefore ? "\n\n" : "\n");
            }
            cleaned.append(kept);
            blankBefore = false;
        }
        return cleaned.toString();
    }
}
