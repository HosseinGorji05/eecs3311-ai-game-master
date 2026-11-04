package agent;

import com.google.gson.JsonElement;
import engine.character.Enemy;
import engine.character.Player;
import engine.core.Ending;
import engine.core.EndingCalculator;
import engine.core.EndingType;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Item;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;
import engine.world.World;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the epilogue of a finished game within the ending type the rules chose (F12, SD09): the
 * model answers {@code {"epilogue":"..."}} with 1-{@value #MAX_PARAGRAPHS} paragraphs.
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern. The prompt holds the ending
 * type, the statistics, the player's name and class, the main quest's outcome, the names that exist
 * and the bounded story-memory context (fixed facts first), all marked as data. {@link #parse} keeps
 * the paragraphs, removes other control characters and cuts the text to {@value #MAX_EPILOGUE_CHARS}
 * characters before anything stores it; the epilogue is only ever shown.
 *
 * <p>{@link #isValid} rejects an epilogue that
 * <ul>
 *   <li>has more than {@value #MAX_PARAGRAPHS} paragraphs;</li>
 *   <li>names someone or something that does not exist: a capitalized word inside a sentence that
 *       appears in no name of the world (people, places, regions, items, enemies), the player's name
 *       or class, a quest title, a recorded event (dialogue aside) or a fixed fact;</li>
 *   <li>contradicts the recorded facts: a defeated enemy said to live on, an enemy that was never
 *       defeated said to be defeated, a completed quest said to fail or a failed one said to succeed,
 *       or the relic said to be recovered when the main quest was not completed;</li>
 *   <li>contradicts the ending type: a DEATH ending must tell of a death and may not say the player
 *       survived or triumphed; any other ending may not say the player died; HEROIC may not call the
 *       player a villain, DARK may not call the player a hero, NEUTRAL neither extreme
 *       (the word lists below).</li>
 * </ul>
 * After two rejected or failed attempts, or without a model, the fallback is a template epilogue for
 * the ending type filled with the player's name, class and statistics. A player who died before
 * completing any quest gets a short DEATH template without asking the model (UC13 1a).
 */
public class EndingWriter extends LlmTask<Ending, String> {

    /** Longest epilogue; a longer answer from the model is cut before it is stored. */
    static final int MAX_EPILOGUE_CHARS = 1500;
    /** Most paragraphs an epilogue may have (F12: 1-3). */
    static final int MAX_PARAGRAPHS = 3;
    private static final int MAX_TOKENS = 700;

    /** The fallback reason when the short DEATH template is used without asking the model. */
    static final String DIED_BEFORE_ANY_QUEST = "the player died before completing any quest";

    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
    private static final String SENTENCE_ENDS = ".!?:;\n";
    private static final String OPENERS = "\"'“‘([";

    /** Words that must appear in a DEATH epilogue: it tells of a death. */
    private static final Pattern DEATH_WORDS = Pattern.compile(
            "\\b(died|dies|death|dead|perished|slain|killed|fell|fallen|last breath|grave)\\b");
    /** Words no DEATH epilogue may use: the player did not survive or triumph. */
    private static final Pattern TRIUMPH_WORDS = Pattern.compile(
            "\\b(survived|survives|triumphant|triumphed|victorious|hero's welcome|lived happily|returned home|returns home)\\b");
    /** Words a HEROIC epilogue may not use about how the player is remembered. */
    private static final Pattern VILLAIN_WORDS = Pattern.compile(
            "\\b(villain|villainous|despised|disgrace|disgraced|infamous|betrayed|betrayal)\\b");
    /** Words a DARK epilogue may not use about how the player is remembered. */
    private static final Pattern HERO_WORDS = Pattern.compile("\\b(hero|heroes|heroic|beloved|celebrated|cheered)\\b");
    /** Words a NEUTRAL epilogue may not use: neither extreme. */
    private static final Pattern EXTREME_WORDS = Pattern.compile(
            "\\b(hero's welcome|beloved|despised|disgraced|infamous|villainous)\\b");
    /** Verbs that, before an enemy's name (and without "by"), say it was defeated. */
    private static final Pattern DEFEAT_VERBS = Pattern.compile("\\b(defeated|slew|vanquished|killed|slain|struck down)\\b");
    /** Verbs that, with "relic", say it was recovered. */
    private static final Pattern RECOVERED_WORDS = Pattern.compile("\\b(recovered|returned|brought back|reclaimed|restored|took back)\\b");
    /** Words that turn a sentence about the relic into a denial. */
    private static final Pattern NEGATIONS = Pattern.compile("\\b(never|not|failed|without|no one|nobody)\\b|n't\\b");
    private static final List<String> FAILED_WORDS = List.of("fail");
    private static final List<String> COMPLETED_WORDS = List.of("complet", "succeed", "success");

    /** How each ending type should read, for the prompt. */
    private static final Map<EndingType, String> TONES = Map.of(
            EndingType.HEROIC, "the relic is home and the player helped the people who asked; tell of gratitude and a hero remembered.",
            EndingType.NEUTRAL, "the relic is home, but the player was neither hero nor villain; tell it plainly, without praise or blame.",
            EndingType.DARK, "the relic is home, but the player's thefts and broken promises are remembered; tell it darkly and never call the player a hero.",
            EndingType.DEATH, "the player died; tell of the death and what it left behind, and never say the player survived or triumphed.");

    private boolean shortDeathUsed;

    public EndingWriter(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /**
     * The epilogue for this ending: the short DEATH template if the player died before completing any
     * quest, otherwise the model's epilogue or the template for the ending type. Never fails.
     */
    public String write(Ending ending) {
        shortDeathUsed = ending.getType() == EndingType.DEATH && ending.getStatistic(EndingCalculator.QUESTS_COMPLETED) == 0;
        if (shortDeathUsed) {
            return shortDeathEpilogue(ending);
        }
        try {
            return run(ending);
        } catch (RuntimeException unexpected) {
            return fallback(ending);
        }
    }

    /** Whether the last {@link #write} used a template, the short DEATH one included. */
    @Override
    public boolean isFallbackUsed() {
        return shortDeathUsed || super.isFallbackUsed();
    }

    /** Why the last {@link #write} used a template, or {@code null}. */
    @Override
    public String getFallbackReason() {
        return shortDeathUsed ? DIED_BEFORE_ANY_QUEST : super.getFallbackReason();
    }

    @Override
    protected LlmRequest buildPrompt(Ending ending) {
        Player player = state.getPlayer();
        Quest main = mainQuest();
        String system = prompts.fromTemplate("ending.system", Map.of(
                "player", player.getName(),
                "type", ending.getType().name(),
                "tone", TONES.get(ending.getType()),
                "maxParagraphs", String.valueOf(MAX_PARAGRAPHS),
                "maxChars", String.valueOf(MAX_EPILOGUE_CHARS)));
        String user = prompts.fromTemplate("ending.user", Map.of(
                "player", player.getName(),
                "class", className(player),
                "type", ending.getType().name(),
                "mainQuest", main == null ? "none" : "\"" + main.getTitle() + "\", "
                        + main.getStatus().name().toLowerCase(Locale.ROOT),
                "statistics", ending.getStatistics().entrySet().stream()
                        .map(entry -> entry.getKey() + " " + entry.getValue()).collect(Collectors.joining(", ")),
                "names", String.join(", ", knownNames()),
                "memory", prompts.memoryContext(null, PromptBuilder.MAX_MEMORY_CHARS)));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /** The epilogue with its paragraphs kept and cut to {@value #MAX_EPILOGUE_CHARS} characters; {@code null} if there is none. */
    @Override
    protected String parse(LlmResponse response) {
        JsonElement root = parser.parseJson(response.getText(), JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        String epilogue = ActionInterpreter.field(root.getAsJsonObject(), Set.of("epilogue"));
        if (epilogue == null || epilogue.isBlank()) {
            return null;
        }
        return NpcDialogueAgent.truncate(clean(epilogue), MAX_EPILOGUE_CHARS);
    }

    @Override
    protected boolean isValid(String epilogue, Ending ending) {
        return problem(epilogue, ending) == null;
    }

    @Override
    protected String describeInvalid(String epilogue, Ending ending) {
        String problem = problem(epilogue, ending);
        return problem == null ? super.describeInvalid(epilogue, ending) : problem;
    }

    /** The template epilogue for the ending type, filled with the player's name, class and statistics. */
    @Override
    protected String fallback(Ending ending) {
        Player player = state.getPlayer();
        String name = player.getName();
        String cls = className(player);
        String deeds = "In " + count(ending.getStatistic(EndingCalculator.TURNS_PLAYED), "turn", "turns") + ", " + name
                + " completed " + count(ending.getStatistic(EndingCalculator.QUESTS_COMPLETED), "quest", "quests")
                + " and defeated " + count(ending.getStatistic(EndingCalculator.ENEMIES_DEFEATED), "enemy", "enemies");
        return switch (ending.getType()) {
            case HEROIC -> name + " the " + cls + " brought the stolen village relic home from the Bandit Camp, and the"
                    + " whole village turned out to cheer. " + deeds + ", and kept faith with everyone who asked for help.\n\n"
                    + "Long after, the villagers still told the story of the " + cls + " who stood by them when it"
                    + " mattered, and the relic never left the village again.";
            case NEUTRAL -> name + " the " + cls + " brought the village relic back from the Bandit Camp. " + deeds + ".\n\n"
                    + "The villagers were glad to have the relic home, though few could say what kind of person had"
                    + " returned it. " + name + " moved on soon after, and the village went back to its quiet ways.";
            case DARK -> name + " the " + cls + " took the village relic back from the bandits, but the village remembers"
                    + " the cost. " + deeds + ", and left a trail of broken trust behind.\n\n"
                    + "The relic is home again, yet the villagers lower their voices when the " + cls + " comes up in"
                    + " talk, and doors close a little faster when a stranger walks in.";
            case DEATH -> name + " the " + cls + " died before the village relic could be recovered. " + deeds + ".\n\n"
                    + "The village mourned the " + cls + " who had tried to help, and the relic stayed in the bandits' hands.";
        };
    }

    /** The short DEATH template for a player who died before completing any quest (UC13 1a). */
    private String shortDeathEpilogue(Ending ending) {
        Player player = state.getPlayer();
        return player.getName() + " the " + className(player) + " died after "
                + count(ending.getStatistic(EndingCalculator.TURNS_PLAYED), "turn", "turns")
                + ", before completing a single quest. The village relic stayed in the bandits' hands.";
    }

    /** What is wrong with the epilogue, described, or {@code null} if it is valid (see the class comment). */
    private String problem(String epilogue, Ending ending) {
        long paragraphs = PARAGRAPH_BREAK.splitAsStream(epilogue.strip()).filter(part -> !part.isBlank()).count();
        if (paragraphs > MAX_PARAGRAPHS) {
            return "the epilogue has " + paragraphs + " paragraphs";
        }
        String invented = inventedName(epilogue);
        if (invented != null) {
            return "the epilogue names \"" + invented + "\", which is not in the game";
        }
        String text = epilogue.toLowerCase(Locale.ROOT);
        List<String> sentences = List.of(text.split("[.!?;\\n]"));
        String contradiction = factContradiction(sentences);
        return contradiction != null ? contradiction : typeContradiction(text, ending.getType());
    }

    /** The first capitalized word inside a sentence that is no known name, or {@code null}. */
    private String inventedName(String epilogue) {
        Set<String> known = knownWords();
        Matcher word = WORD.matcher(epilogue);
        while (word.find()) {
            String token = word.group();
            if (Character.isUpperCase(token.charAt(0)) && !token.equals("I") && !startsSentence(epilogue, word.start())
                    && !known.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        return null;
    }

    /** Whether the word at {@code index} begins a sentence, a line or a quotation. */
    private static boolean startsSentence(String text, int index) {
        int i = index - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t' || OPENERS.indexOf(text.charAt(i)) >= 0)) {
            if (OPENERS.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
            i--;
        }
        return i < 0 || SENTENCE_ENDS.indexOf(text.charAt(i)) >= 0;
    }

    private String factContradiction(List<String> sentences) {
        Set<String> defeated = defeatedEnemies();
        for (String enemy : defeated) {
            if (mentionedWith(sentences, enemy, MemorySummarizer.ALIVE_WORDS)) {
                return "the epilogue says the defeated " + enemy + " lives on";
            }
        }
        for (String enemy : livingEnemies()) {
            if (!defeated.contains(enemy) && claimsDefeated(sentences, enemy.toLowerCase(Locale.ROOT))) {
                return "the epilogue says the " + enemy + " was defeated, but it never was";
            }
        }
        for (Quest quest : state.getQuestLog().allQuests()) {
            if (quest.getStatus() == QuestStatus.COMPLETED && mentionedWith(sentences, quest.getTitle(), FAILED_WORDS)) {
                return "the epilogue says the completed quest \"" + quest.getTitle() + "\" failed";
            }
            if (quest.getStatus() == QuestStatus.FAILED && mentionedWith(sentences, quest.getTitle(), COMPLETED_WORDS)) {
                return "the epilogue says the failed quest \"" + quest.getTitle() + "\" was completed";
            }
        }
        Quest main = mainQuest();
        if (main == null || main.getStatus() != QuestStatus.COMPLETED) {
            for (String sentence : sentences) {
                if (sentence.contains("relic") && RECOVERED_WORDS.matcher(sentence).find()
                        && !NEGATIONS.matcher(sentence).find()) {
                    return "the epilogue says the relic was recovered, but the main quest was not completed";
                }
            }
        }
        return null;
    }

    private String typeContradiction(String text, EndingType type) {
        String name = Pattern.quote(state.getPlayer().getName().toLowerCase(Locale.ROOT));
        if (type == EndingType.DEATH) {
            if (!DEATH_WORDS.matcher(text).find()) {
                return "the DEATH epilogue does not tell of a death";
            }
            if (TRIUMPH_WORDS.matcher(text).find()
                    || Pattern.compile("\\b" + name + "\\s+(survived|escaped|returned|lived|triumphed|won)\\b").matcher(text).find()) {
                return "the DEATH epilogue says the player survived or triumphed";
            }
            return null;
        }
        if (Pattern.compile("\\b" + name + "('s death|\\s+(died|perished|was slain|was killed|fell in battle|lost (his|her|their) life))\\b")
                .matcher(text).find()) {
            return "the " + type + " epilogue says the player died";
        }
        Pattern forbidden = switch (type) {
            case HEROIC -> VILLAIN_WORDS;
            case DARK -> HERO_WORDS;
            default -> EXTREME_WORDS;
        };
        Matcher wrongTone = forbidden.matcher(text);
        return wrongTone.find() ? "the " + type + " epilogue says \"" + wrongTone.group() + "\"" : null;
    }

    /** A sentence where a defeat verb comes before the enemy's name and no "by" says who did it to whom. */
    private static boolean claimsDefeated(List<String> sentences, String enemy) {
        for (String sentence : sentences) {
            int at = sentence.indexOf(enemy);
            if (at > 0 && !sentence.contains(" by ") && DEFEAT_VERBS.matcher(sentence.substring(0, at)).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether one sentence names {@code subject} and also contains one of {@code words}. */
    private static boolean mentionedWith(List<String> sentences, String subject, List<String> words) {
        String name = subject.toLowerCase(Locale.ROOT);
        return sentences.stream().anyMatch(sentence -> sentence.contains(name) && words.stream().anyMatch(sentence::contains));
    }

    /** The enemies defeated in this game, from the event log. */
    private Set<String> defeatedEnemies() {
        Set<String> enemies = new LinkedHashSet<>();
        for (GameEvent event : state.getEventLog().filter(EventType.COMBAT)) {
            if (event.getVerb() == EventVerb.DEFEATED && event.getSubjectId() != null) {
                enemies.add(event.getSubjectId());
            }
        }
        return enemies;
    }

    private List<String> livingEnemies() {
        List<String> enemies = new ArrayList<>();
        for (Location location : locations()) {
            location.getEnemies().stream().map(Enemy::getName).forEach(enemies::add);
        }
        return enemies;
    }

    /** The names of the people, places, regions, items and enemies, living and defeated, for the prompt. */
    private Set<String> knownNames() {
        Set<String> names = new LinkedHashSet<>();
        World world = state.getWorld();
        for (Region region : world.getRegions()) {
            names.add(region.getName());
            for (Location location : region.getLocations()) {
                names.add(location.getName());
                for (NPC npc : location.getNpcs()) {
                    names.add(npc.getName() + " the " + npc.getRole());
                }
                addItemNames(names, location.getItems());
            }
        }
        addItemNames(names, state.getPlayer().getInventory().getItems());
        names.addAll(livingEnemies());
        names.addAll(defeatedEnemies());
        return names;
    }

    /** Every word an epilogue may capitalize inside a sentence, lower case (see the class comment). */
    private Set<String> knownWords() {
        List<String> sources = new ArrayList<>(knownNames());
        Player player = state.getPlayer();
        sources.add(player.getName());
        sources.add(className(player));
        state.getQuestLog().allQuests().forEach(quest -> sources.add(quest.getTitle()));
        state.getEventLog().recent(Integer.MAX_VALUE).stream()
                .filter(event -> event.getType() != EventType.DIALOGUE)
                .forEach(event -> sources.add(event.getDescription()));
        sources.addAll(state.getStoryMemory().getFixedFacts());
        Set<String> words = new LinkedHashSet<>();
        for (String source : sources) {
            Matcher word = WORD.matcher(source);
            while (word.find()) {
                words.add(word.group().toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private static void addItemNames(Set<String> names, List<Item> items) {
        for (Item item : items) {
            names.add(item.getName());
            if (item instanceof Container container) {
                addItemNames(names, container.getContents());
            }
        }
    }

    private List<Location> locations() {
        List<Location> all = new ArrayList<>();
        state.getWorld().getRegions().forEach(region -> all.addAll(region.getLocations()));
        return all;
    }

    private Quest mainQuest() {
        return state.getQuestLog().allQuests().stream().filter(Quest::isMain).findFirst().orElse(null);
    }

    /** Paragraphs separated by one blank line, each on one line, without other control characters. */
    private static String clean(String text) {
        return PARAGRAPH_BREAK.splitAsStream(text.replace("\r\n", "\n").replace('\r', '\n'))
                .map(paragraph -> paragraph.replaceAll("[\\p{Cntrl}\\s]+", " ").strip())
                .filter(paragraph -> !paragraph.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    private static String className(Player player) {
        return player.getCharacterClass().name().toLowerCase(Locale.ROOT);
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
