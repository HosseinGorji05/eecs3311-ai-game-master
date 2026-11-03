package agent;

import com.google.gson.JsonElement;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import llm.LLMClient;
import llm.LlmRequest;
import llm.LlmResponse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Condenses a batch of older events into one short note for the story memory (F09, SD06): the
 * model answers {@code {"summary":"..."}}.
 *
 * <p>Pattern role: <b>ConcreteClass</b> of the Template Method pattern. The prompt holds the fixed
 * facts and the events, both marked as data. {@link #parse} puts the summary on one line and cuts it
 * to {@value #MAX_SUMMARY_CHARS} characters before anything stores it. {@link #isValid} rejects a
 * summary that contradicts a fixed fact (F09: "the fixed fact wins; the summary is discarded and
 * retried"): a sentence that names a defeated enemy together with a word saying it lives on
 * ({@link #ALIVE_WORDS}), a completed quest together with "fail", or a failed quest together with
 * "complete" or "succeed". The fallback is {@link #fallbackSummary}: the events' own descriptions,
 * joined, so memory works fully offline.
 */
public class MemorySummarizer extends LlmTask<List<GameEvent>, String> {

    /** Longest summary; a longer answer from the model is cut before it is stored. */
    static final int MAX_SUMMARY_CHARS = 400;
    /** Longest part of one event's description in the fallback summary. */
    static final int MAX_FALLBACK_EVENT_CHARS = 80;
    private static final int MAX_TOKENS = 300;

    /** Words that, in a sentence naming a defeated enemy, say it is not dead. */
    static final List<String> ALIVE_WORDS = List.of("alive", "surviv", "escap", "got away");
    private static final List<String> FAILED_WORDS = List.of("fail");
    private static final List<String> COMPLETED_WORDS = List.of("complet", "succeed", "success");

    public MemorySummarizer(LLMClient llm, PromptBuilder prompts, ResponseParser parser, GameState state) {
        super(llm, prompts, parser, state);
    }

    /** A short note about the events, from the model or, failing that, from their descriptions. */
    public String summarize(List<GameEvent> events) {
        return run(List.copyOf(events));
    }

    @Override
    protected LlmRequest buildPrompt(List<GameEvent> events) {
        List<String> facts = state.getStoryMemory().getFixedFacts();
        String system = prompts.fromTemplate("summary.system", Map.of("maxChars", String.valueOf(MAX_SUMMARY_CHARS)));
        String user = prompts.fromTemplate("summary.user", Map.of(
                "facts", facts.isEmpty() ? "none" : facts.stream().map(fact -> "- " + fact).collect(Collectors.joining("\n")),
                "events", events.stream().map(event -> "- [turn " + event.getTurn() + "] " + event.getDescription())
                        .collect(Collectors.joining("\n"))));
        return new LlmRequest(system, user, true, MAX_TOKENS);
    }

    /** The summary on one line and cut to {@value #MAX_SUMMARY_CHARS} characters; {@code null} if there is none. */
    @Override
    protected String parse(LlmResponse response) {
        JsonElement root = parser.parseJson(response.getText(), JsonElement.class);
        if (root == null || !root.isJsonObject()) {
            return null;
        }
        String summary = ActionInterpreter.field(root.getAsJsonObject(), Set.of("summary"));
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return NpcDialogueAgent.truncate(oneLine(summary), MAX_SUMMARY_CHARS);
    }

    @Override
    protected boolean isValid(String summary, List<GameEvent> events) {
        return contradiction(summary, events) == null;
    }

    @Override
    protected String describeInvalid(String summary, List<GameEvent> events) {
        String contradiction = contradiction(summary, events);
        return contradiction == null ? super.describeInvalid(summary, events) : contradiction;
    }

    @Override
    protected String fallback(List<GameEvent> events) {
        return fallbackSummary(events);
    }

    /**
     * The events' descriptions, each cut to {@value #MAX_FALLBACK_EVENT_CHARS} characters, joined with
     * "; "; if that is longer than {@value #MAX_SUMMARY_CHARS} characters, the oldest are dropped first.
     */
    static String fallbackSummary(List<GameEvent> events) {
        List<String> parts = new ArrayList<>();
        for (GameEvent event : events) {
            String part = NpcDialogueAgent.truncate(oneLine(event.getDescription()), MAX_FALLBACK_EVENT_CHARS);
            parts.add(part.endsWith(".") && !part.endsWith("...") ? part.substring(0, part.length() - 1) : part);
        }
        while (parts.size() > 1 && String.join("; ", parts).length() + 1 > MAX_SUMMARY_CHARS) {
            parts.remove(0);
        }
        return parts.isEmpty() ? "Nothing of note happened." : String.join("; ", parts) + ".";
    }

    /** The first fixed fact the summary contradicts, described, or {@code null} if it contradicts none. */
    private String contradiction(String summary, List<GameEvent> events) {
        List<String> sentences = List.of(summary.toLowerCase(Locale.ROOT).split("[.!?;]"));
        for (String enemy : defeatedEnemies(events)) {
            if (mentionedWith(sentences, enemy, ALIVE_WORDS)) {
                return "the summary says the defeated " + enemy + " is alive";
            }
        }
        for (Quest quest : state.getQuestLog().allQuests()) {
            if (quest.getStatus() == QuestStatus.COMPLETED && mentionedWith(sentences, quest.getTitle(), FAILED_WORDS)) {
                return "the summary says the completed quest \"" + quest.getTitle() + "\" failed";
            }
            if (quest.getStatus() == QuestStatus.FAILED && mentionedWith(sentences, quest.getTitle(), COMPLETED_WORDS)) {
                return "the summary says the failed quest \"" + quest.getTitle() + "\" was completed";
            }
        }
        return null;
    }

    /** The enemies defeated so far: in the event log and in the events being summarized. */
    private Set<String> defeatedEnemies(List<GameEvent> events) {
        List<GameEvent> combat = new ArrayList<>(state.getEventLog().filter(EventType.COMBAT));
        combat.addAll(events);
        Set<String> enemies = new LinkedHashSet<>();
        for (GameEvent event : combat) {
            if (event.getVerb() == EventVerb.DEFEATED && event.getSubjectId() != null) {
                enemies.add(event.getSubjectId());
            }
        }
        return enemies;
    }

    /** Whether one sentence names {@code subject} and also contains one of {@code words}. */
    private static boolean mentionedWith(List<String> sentences, String subject, List<String> words) {
        String name = subject.toLowerCase(Locale.ROOT);
        return sentences.stream().anyMatch(sentence -> sentence.contains(name) && words.stream().anyMatch(sentence::contains));
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }
}
