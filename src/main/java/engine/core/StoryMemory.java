package engine.core;

import engine.events.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the game remembers of the story (F09): fixed facts that are never summarized away, short
 * summaries of older events, and the recent events in full.
 *
 * <p>Everything stored here is plain data for prompts and the journal, never instructions. Each
 * text is stored on one line without control characters and cut to a length limit
 * ({@value #MAX_ENTRY_CHARS} characters for a fact or summary, {@value #MAX_EVENT_CHARS} for an
 * event's description), so text that came from a model reply cannot grow the memory. At most
 * {@value #MAX_SUMMARIES} summaries are kept; when another is added, the oldest is dropped. Recent
 * events are taken out by the memory manager, which summarizes them (SD06).
 */
public class StoryMemory {

    /** Longest fixed fact or summary. */
    public static final int MAX_ENTRY_CHARS = 450;
    /** Longest event description; a longer one is stored as a shortened copy of the event. */
    public static final int MAX_EVENT_CHARS = 300;
    /** How many summaries are kept. */
    public static final int MAX_SUMMARIES = 8;

    private final List<String> fixedFacts = new ArrayList<>();
    private final List<String> summaries = new ArrayList<>();
    private final List<GameEvent> recentEvents = new ArrayList<>();

    /** Adds an event to the recent events, with its description cleaned and cut if needed. */
    public void record(GameEvent event) {
        Objects.requireNonNull(event, "event");
        String description = clean(event.getDescription(), MAX_EVENT_CHARS);
        recentEvents.add(description.equals(event.getDescription()) ? event
                : new GameEvent(event.getType(), event.getTurn(), description, event.getSubjectId(),
                        event.getAlignmentDelta(), event.getVerb(), event.getTimestamp()));
    }

    /** Adds a fact that is never summarized away; a blank fact or one already known is ignored. */
    public void addFixedFact(String fact) {
        String cleaned = clean(Objects.requireNonNull(fact, "fact"), MAX_ENTRY_CHARS);
        if (!cleaned.isEmpty() && !fixedFacts.contains(cleaned)) {
            fixedFacts.add(cleaned);
        }
    }

    /** How many recent events are stored. */
    public int recentCount() {
        return recentEvents.size();
    }

    /**
     * Removes and returns the {@code n} oldest recent events (all of them if there are fewer), oldest first.
     *
     * @throws IllegalArgumentException if {@code n} is negative
     */
    public List<GameEvent> takeOldest(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("Count must not be negative: " + n);
        }
        List<GameEvent> oldest = recentEvents.subList(0, Math.min(n, recentEvents.size()));
        List<GameEvent> taken = List.copyOf(oldest);
        oldest.clear();
        return taken;
    }

    /** Adds a summary of older events, dropping the oldest summary beyond {@value #MAX_SUMMARIES}; a blank one is ignored. */
    public void addSummary(String summary) {
        String cleaned = clean(Objects.requireNonNull(summary, "summary"), MAX_ENTRY_CHARS);
        if (cleaned.isEmpty()) {
            return;
        }
        summaries.add(cleaned);
        if (summaries.size() > MAX_SUMMARIES) {
            summaries.remove(0);
        }
    }

    /**
     * Replaces the whole memory (when a saved game is loaded). Every text goes through the same
     * cleaning and limits as when it was first stored.
     */
    public void restore(List<String> facts, List<String> savedSummaries, List<GameEvent> events) {
        List<String> factsCopy = List.copyOf(facts);
        List<String> summariesCopy = List.copyOf(savedSummaries);
        List<GameEvent> eventsCopy = List.copyOf(events);
        fixedFacts.clear();
        summaries.clear();
        recentEvents.clear();
        factsCopy.forEach(this::addFixedFact);
        summariesCopy.forEach(this::addSummary);
        eventsCopy.forEach(this::record);
    }

    /** The fixed facts, oldest first, as a read-only list. */
    public List<String> getFixedFacts() {
        return List.copyOf(fixedFacts);
    }

    /** The summaries, oldest first, as a read-only list. */
    public List<String> getSummaries() {
        return List.copyOf(summaries);
    }

    /** The recent events, oldest first, as a read-only list. */
    public List<GameEvent> getRecentEvents() {
        return List.copyOf(recentEvents);
    }

    /**
     * The text on one line (control characters and runs of whitespace become one space), cut at a
     * word boundary with "..." if it is longer than {@code maxChars}.
     */
    static String clean(String text, int maxChars) {
        String line = text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (line.length() <= maxChars) {
            return line;
        }
        String head = line.substring(0, maxChars - 3);
        int space = head.lastIndexOf(' ');
        return (space > maxChars / 2 ? head.substring(0, space) : head).stripTrailing() + "...";
    }
}
