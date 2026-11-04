package engine.core;

import engine.events.EventLog;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decides how the game ended, deterministically, from the event log (F12, SD09). The LLM never
 * chooses the ending type or the statistics; it only writes the epilogue within them.
 *
 * <p>The alignment score is the sum of every event's alignment delta: +2 per side quest completed
 * for an NPC, -1 per failed side quest, -2 per item stolen from an NPC (Stage 1 decision "Tuning
 * numbers").
 */
public class EndingCalculator {

    /** Score at or above which a surviving player gets the HEROIC ending. */
    public static final int HEROIC_THRESHOLD = 3;
    /** Score at or below which a surviving player gets the DARK ending. */
    public static final int DARK_THRESHOLD = -3;

    /** Statistic: the turn on which the game ended (the last recorded event's turn). */
    public static final String TURNS_PLAYED = "turns played";
    /** Statistic: quests completed, the main quest included. */
    public static final String QUESTS_COMPLETED = "quests completed";
    /** Statistic: enemies defeated. */
    public static final String ENEMIES_DEFEATED = "enemies defeated";

    /** The sum of {@code getAlignmentDelta()} over all events (SD09 n2). */
    public int alignmentScore(EventLog log) {
        return events(log).stream().mapToInt(GameEvent::getAlignmentDelta).sum();
    }

    /** DEATH if the player died; otherwise HEROIC at {@value #HEROIC_THRESHOLD} or more, DARK at {@value #DARK_THRESHOLD} or less, NEUTRAL in between (SD09 n3). */
    public EndingType determineType(GameState state, int score) {
        Objects.requireNonNull(state, "state");
        if (!state.getPlayer().isAlive()) {
            return EndingType.DEATH;
        }
        if (score >= HEROIC_THRESHOLD) {
            return EndingType.HEROIC;
        }
        return score <= DARK_THRESHOLD ? EndingType.DARK : EndingType.NEUTRAL;
    }

    /**
     * {@value #TURNS_PLAYED} (the last event's turn; the turn that ends the game always records an
     * event, 0 if there are none), {@value #QUESTS_COMPLETED} and {@value #ENEMIES_DEFEATED}, in this order.
     */
    public Map<String, Integer> statistics(EventLog log) {
        List<GameEvent> events = events(log);
        Map<String, Integer> statistics = new LinkedHashMap<>();
        statistics.put(TURNS_PLAYED, events.stream().mapToInt(GameEvent::getTurn).max().orElse(0));
        statistics.put(QUESTS_COMPLETED, count(events, EventType.QUEST, EventVerb.COMPLETED));
        statistics.put(ENEMIES_DEFEATED, count(events, EventType.COMBAT, EventVerb.DEFEATED));
        return statistics;
    }

    private static List<GameEvent> events(EventLog log) {
        return Objects.requireNonNull(log, "log").recent(Integer.MAX_VALUE);
    }

    private static int count(List<GameEvent> events, EventType type, EventVerb verb) {
        return (int) events.stream().filter(event -> event.getType() == type && event.getVerb() == verb).count();
    }
}
