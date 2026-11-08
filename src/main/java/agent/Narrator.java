package agent;

import com.google.gson.JsonElement;
import engine.character.Enemy;
import engine.commands.ActionResult;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Item;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Narrates the real results of a typed turn (F02, F04, SD02): {@value #MIN_SENTENCES}-{@value #MAX_SENTENCES}
 * sentences that describe only what the rules engine decided.
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern ({@code LlmTask<List<ActionResult>,
 * String>}). The prompt holds the player's and the location's names, the turn's results (the engine's own
 * messages, with every roll, damage and HP, each marked done or refused) and the bounded story-memory
 * context focused on the location, all marked as data; never the player's typed words. The model answers
 * {@code {"narration":"..."}} (a reply that is plain text with no JSON at all is read as the narration). The
 * text is cleaned like a backstory (control and invisible characters removed).
 *
 * <p>{@link #isValid} checks the narration against the results, deterministically ({@link Rule}, in this
 * order): {@link Rule#EMPTY}; {@link Rule#TOO_LONG} (over {@value #MAX_NARRATION_CHARS} characters);
 * {@link Rule#MARKUP} and {@link Rule#INSTRUCTION} (as for the backstory); {@link Rule#SENTENCES}
 * ({@value #MIN_SENTENCES}-{@value #MAX_SENTENCES}, the last one finished); {@link Rule#NUMBERS} (every number
 * written in digits appears in the results, a number before "damage" is a damage the results deal and a
 * number before "HP" is an HP value the results show); {@link Rule#HIT_MISS} (no hit words when no attack hit, no miss
 * words when no attack missed); {@link Rule#DEFEAT} (a defeated enemy is not said to be alive or to escape;
 * without a defeat or a death nobody is said to be killed, slain or dead); {@link Rule#UNSUPPORTED_EVENT}
 * (no unlocking or opened door unless the results unlock it, no taking unless an item was taken, no
 * completed quest, and no gold, coins, reward or XP unless the results mention them); {@link
 * Rule#INVENTED_NAME} (every capitalized word inside a sentence is a word of the results, of a name in the
 * world or of the player). The word lists are checked as whole words, ignoring case.
 *
 * <p>After one retry (none after HTTP 401 or 403), or without a model, the fallback is the template: the
 * results' own messages, one per line, exactly as the game shows them without narration. The narration is
 * data: it changes nothing and is never fed back to the engine.
 */
public class Narrator extends LlmTask<List<ActionResult>, String> {

    /** Longest narration. */
    public static final int MAX_NARRATION_CHARS = 400;
    /** Fewest and most sentences (F02: "a 2-4 sentence narration"). */
    public static final int MIN_SENTENCES = 2;
    public static final int MAX_SENTENCES = 4;

    private static final int MAX_TOKENS = 250;
    private static final Set<String> NARRATION_FIELDS = Set.of("narration", "text", "story");
    private static final Pattern ATTACK = Pattern.compile(
            "rolls \\d+ \\+ -?\\d+ = -?\\d+ against defense -?\\d+\\. (Miss\\.|Hit for \\d+ damage)");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern RESULT_DAMAGE = Pattern.compile("Hit for (\\d+) damage");
    private static final Pattern RESULT_HP = Pattern.compile("(\\d+)/(\\d+) HP");
    private static final Pattern SAID_DAMAGE = Pattern.compile("(?i)\\b(\\d+)\\s+(?:points? of\\s+)?damage\\b");
    private static final Pattern SAID_HP = Pattern.compile("(?i)\\b(\\d+)(?:\\s*/\\s*(\\d+))?\\s*(?:HP|hit points|health)\\b");
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern HIT_WORDS = Pattern.compile(
            "(?i)\\b(hit|hits|struck|strikes?|wounds?|wounded|damage[sd]?|connects?|lands? a blow)\\b");
    private static final Pattern MISS_WORDS = Pattern.compile(
            "(?i)\\b(miss|misses|missed|dodges?|dodged|whiffs?|fails? to hit|glances? off)\\b");
    private static final Pattern DEATH_WORDS = Pattern.compile(
            "(?i)\\b(defeated|defeats?|slain|slays?|slew|killed|kills?|dies|died|dead|lifeless|vanquish(es|ed)?)\\b");
    private static final Pattern UNLOCK_WORDS = Pattern.compile(
            "(?i)\\b(unlocks?|unlocked|unlocking)\\b|\\bdoor\\b[^.!?]*\\b(opens|swings open|creaks open)\\b");
    private static final Pattern TAKE_WORDS = Pattern.compile(
            "(?i)\\b(take|takes|took|taken|pick(s|ed)? up|grab(s|bed)?|pocket(s|ed)?|snatch(es|ed)?)\\b");
    private static final Pattern QUEST_DONE = Pattern.compile(
            "(?i)\\b(complete[sd]?|fulfil+(s|ed)?|finish(es|ed)?)\\b[^.!?]*\\bquest\\b"
                    + "|\\bquest\\b[^.!?]*\\b(complete[sd]?|fulfil+(s|ed)?|finish(es|ed)?|done)\\b");
    private static final Pattern WEALTH_WORDS = Pattern.compile("(?i)\\b(gold|coins?|reward|rewards|riches|xp|experience)\\b");
    private static final String SENTENCE_ENDS = ".!?";
    private static final String OPENERS = "\"'(“‘";

    /** The rules a narration must keep, named in the debug output. */
    enum Rule {
        EMPTY,
        TOO_LONG,
        MARKUP,
        INSTRUCTION,
        SENTENCES,
        NUMBERS,
        HIT_MISS,
        DEFEAT,
        UNSUPPORTED_EVENT,
        INVENTED_NAME
    }

    public Narrator(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /** The narration of the turn's results: the model's if it gives a valid one, else the template. Never fails. */
    public String narrate(List<ActionResult> results) {
        return run(List.copyOf(Objects.requireNonNull(results, "results")));
    }

    /** The results' own messages, one per line: what the game shows without narration. */
    public static String template(List<ActionResult> results) {
        return results.stream().map(ActionResult::getMessage).collect(Collectors.joining("\n"));
    }

    @Override
    protected LlmRequest buildPrompt(List<ActionResult> results) {
        String system = prompts.fromTemplate("narration.system", Map.of(
                "minSentences", String.valueOf(MIN_SENTENCES),
                "maxSentences", String.valueOf(MAX_SENTENCES),
                "maxChars", String.valueOf(MAX_NARRATION_CHARS)));
        String resultLines = results.stream()
                .map(result -> "- " + (result.isSuccess() ? "[done] " : "[refused] ")
                        + result.getMessage().replace("\n", "\n  "))
                .collect(Collectors.joining("\n"));
        String user = prompts.fromTemplate("narration.user", Map.of(
                "player", state.getPlayer().getName(),
                "location", state.currentLocation().getName(),
                "results", resultLines,
                "memory", prompts.memoryContext(state.currentLocation().getId(), PromptBuilder.MAX_MEMORY_CHARS)));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /** The cleaned narration from {@code {"narration":"..."}} (or {@code text}, {@code story}), or a plain-text reply. */
    @Override
    protected String parse(LlmResponse response) {
        String text = response.getText();
        if (text == null) {
            return null;
        }
        if (text.indexOf('{') < 0) {
            return BackstoryWriter.clean(text);
        }
        JsonElement root = parser.parseJson(text, JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        String narration = ActionInterpreter.field(root.getAsJsonObject(), NARRATION_FIELDS);
        return narration == null ? null : BackstoryWriter.clean(narration);
    }

    @Override
    protected boolean isValid(String narration, List<ActionResult> results) {
        return problem(narration, results) == null;
    }

    @Override
    protected String describeInvalid(String narration, List<ActionResult> results) {
        String problem = problem(narration, results);
        return problem == null ? super.describeInvalid(narration, results) : problem;
    }

    @Override
    protected String fallback(List<ActionResult> results) {
        return template(results);
    }

    /** The first rule the narration breaks, as "RULE: detail", or {@code null}. */
    String problem(String narration, List<ActionResult> results) {
        if (narration == null || narration.isBlank()) {
            return Rule.EMPTY + ": the narration is empty";
        }
        if (narration.length() > MAX_NARRATION_CHARS) {
            return Rule.TOO_LONG + ": " + narration.length() + " characters, at most " + MAX_NARRATION_CHARS;
        }
        String plain = BackstoryWriter.notPlainProse(narration);
        if (plain != null) {
            return plain;
        }
        int sentences = BackstoryWriter.sentenceCount(narration);
        boolean ended = SENTENCE_ENDS.indexOf(lastLetterOrMark(narration)) >= 0;
        if (sentences < MIN_SENTENCES || sentences > MAX_SENTENCES || !ended) {
            return Rule.SENTENCES + ": " + sentences + " sentences" + (ended ? "" : ", the last one unfinished")
                    + ", it needs " + MIN_SENTENCES + "-" + MAX_SENTENCES;
        }
        String resultText = template(results);
        Set<String> resultNumbers = new HashSet<>();
        NUMBER.matcher(resultText).results().forEach(match -> resultNumbers.add(match.group()));
        Matcher number = NUMBER.matcher(narration);
        while (number.find()) {
            if (!resultNumbers.contains(number.group())) {
                return Rule.NUMBERS + ": " + number.group() + " is not a number of the results";
            }
        }
        String numbers = numberContextProblem(narration, resultText);
        if (numbers != null) {
            return Rule.NUMBERS + ": " + numbers;
        }
        String hitMiss = hitMissProblem(narration, resultText);
        if (hitMiss != null) {
            return Rule.HIT_MISS + ": " + hitMiss;
        }
        String defeat = defeatProblem(narration, events(results));
        if (defeat != null) {
            return Rule.DEFEAT + ": " + defeat;
        }
        String unsupported = unsupportedProblem(narration, resultText, events(results));
        if (unsupported != null) {
            return Rule.UNSUPPORTED_EVENT + ": " + unsupported;
        }
        String invented = inventedName(narration, resultText);
        if (invented != null) {
            return Rule.INVENTED_NAME + ": \"" + invented + "\" is not a name in the game or the results";
        }
        return null;
    }

    /** A damage or HP number that is not the results' damage or HP (e.g. the attack bonus called damage). */
    private static String numberContextProblem(String narration, String resultText) {
        Set<String> damages = new HashSet<>();
        RESULT_DAMAGE.matcher(resultText).results().forEach(match -> damages.add(match.group(1)));
        Set<String> hp = new HashSet<>();
        RESULT_HP.matcher(resultText).results().forEach(match -> {
            hp.add(match.group(1));
            hp.add(match.group(2));
        });
        Matcher damage = SAID_DAMAGE.matcher(narration);
        while (damage.find()) {
            if (!damages.contains(damage.group(1))) {
                return damage.group(1) + " damage is not the damage the results deal";
            }
        }
        Matcher health = SAID_HP.matcher(narration);
        while (health.find()) {
            if (!hp.contains(health.group(1)) || health.group(2) != null && !hp.contains(health.group(2))) {
                return health.group() + " is not an HP value of the results";
            }
        }
        return null;
    }

    private static String hitMissProblem(String narration, String resultText) {
        boolean anyHit = false;
        boolean anyMiss = false;
        Matcher attack = ATTACK.matcher(resultText);
        while (attack.find()) {
            if (attack.group(1).startsWith("Miss")) {
                anyMiss = true;
            } else {
                anyHit = true;
            }
        }
        Matcher hit = HIT_WORDS.matcher(narration);
        if (!anyHit && hit.find()) {
            return "it says \"" + hit.group() + "\" but no attack hit";
        }
        Matcher miss = MISS_WORDS.matcher(narration);
        if (!anyMiss && miss.find()) {
            return "it says \"" + miss.group() + "\" but no attack missed";
        }
        return null;
    }

    private static String defeatProblem(String narration, List<GameEvent> events) {
        List<String> defeated = events.stream().filter(event -> event.getVerb() == EventVerb.DEFEATED)
                .map(GameEvent::getSubjectId).toList();
        boolean died = events.stream().anyMatch(event -> event.getVerb() == EventVerb.DIED);
        for (String enemy : defeated) {
            for (String sentence : narration.split("[.!?]")) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                if (lower.contains(enemy.toLowerCase(Locale.ROOT))
                        && MemorySummarizer.ALIVE_WORDS.stream().anyMatch(lower::contains)) {
                    return "the " + enemy + " was defeated, but a sentence says it lives on or escapes";
                }
            }
        }
        Matcher death = DEATH_WORDS.matcher(narration);
        if (defeated.isEmpty() && !died && death.find()) {
            return "it says \"" + death.group() + "\" but nobody was defeated or died";
        }
        return null;
    }

    private static String unsupportedProblem(String narration, String resultText, List<GameEvent> events) {
        String results = resultText.toLowerCase(Locale.ROOT);
        Matcher unlock = UNLOCK_WORDS.matcher(narration);
        if (!results.contains("you unlock") && unlock.find()) {
            return "it says \"" + unlock.group() + "\" but nothing was unlocked";
        }
        boolean taken = events.stream().anyMatch(event -> event.getType() == EventType.ITEM
                && event.getVerb() == EventVerb.TOOK);
        Matcher take = TAKE_WORDS.matcher(narration);
        if (!taken && take.find()) {
            return "it says \"" + take.group() + "\" but nothing was taken";
        }
        Matcher quest = QUEST_DONE.matcher(narration);
        if (quest.find()) {
            return "it says a quest was completed, which the results do not say";
        }
        Matcher wealth = WEALTH_WORDS.matcher(narration);
        while (wealth.find()) {
            if (!results.contains(wealth.group().toLowerCase(Locale.ROOT))) {
                return "it says \"" + wealth.group() + "\" but the results gain nothing of the kind";
            }
        }
        return null;
    }

    /** The first capitalized word inside a sentence that is in neither the results nor a name of the game. */
    private String inventedName(String narration, String resultText) {
        Set<String> known = knownWords(resultText);
        Matcher word = WORD.matcher(narration);
        while (word.find()) {
            String token = word.group();
            if (Character.isUpperCase(token.charAt(0)) && !token.equals("I") && !startsSentence(narration, word.start())
                    && !known.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        return null;
    }

    private Set<String> knownWords(String resultText) {
        List<String> sources = new ArrayList<>();
        sources.add(resultText);
        sources.add(state.getPlayer().getName());
        sources.add(state.getPlayer().getCharacterClass().name());
        for (Region region : state.getWorld().getRegions()) {
            sources.add(region.getName());
            for (Location location : region.getLocations()) {
                sources.add(location.getName());
                for (NPC npc : location.getNpcs()) {
                    sources.add(npc.getName() + " " + npc.getRole());
                }
                for (Enemy enemy : location.getEnemies()) {
                    sources.add(enemy.getName());
                }
                addItemNames(sources, location.getItems());
            }
        }
        addItemNames(sources, state.getPlayer().getInventory().getItems());
        Set<String> words = new HashSet<>();
        for (String source : sources) {
            Matcher word = WORD.matcher(source);
            while (word.find()) {
                words.add(word.group().toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private static void addItemNames(List<String> names, List<Item> items) {
        for (Item item : items) {
            names.add(item.getName());
            if (item instanceof Container container) {
                addItemNames(names, container.getContents());
            }
        }
    }

    private static List<GameEvent> events(List<ActionResult> results) {
        List<GameEvent> events = new ArrayList<>();
        results.forEach(result -> events.addAll(result.getEvents()));
        return events;
    }

    /** Whether the word at {@code index} begins a sentence, a line or a quotation. */
    private static boolean startsSentence(String text, int index) {
        int i = index - 1;
        while (i >= 0 && (Character.isWhitespace(text.charAt(i)) || OPENERS.indexOf(text.charAt(i)) >= 0)) {
            if (OPENERS.indexOf(text.charAt(i)) >= 0 || text.charAt(i) == '\n') {
                return true;
            }
            i--;
        }
        return i < 0 || SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0;
    }

    /** The last character that is not a closing quote or bracket. */
    private static char lastLetterOrMark(String text) {
        int i = text.length() - 1;
        while (i > 0 && "\"')”’".indexOf(text.charAt(i)) >= 0) {
            i--;
        }
        return text.charAt(i);
    }
}
