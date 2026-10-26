package engine.events;

import java.time.Instant;
import java.util.Objects;

/**
 * Something that happened in the game, recorded with the turn it happened in and a timestamp.
 *
 * <p>{@code subjectId} names what the event is about (a location id, an item name, a quest id),
 * and {@code alignmentDelta} is its effect on the ending (e.g. -2 for a theft). The optional
 * {@link EventVerb} tells events of the same type apart, so a quest objective can tell "took the
 * potion" from "used up the potion".
 */
public class GameEvent {

    private final EventType type;
    private final int turn;
    private final Instant timestamp;
    private final String description;
    private final String subjectId;
    private final int alignmentDelta;
    private final EventVerb verb;

    /** Creates an event without a verb. */
    public GameEvent(EventType type, int turn, String description, String subjectId, int alignmentDelta) {
        this(type, turn, description, subjectId, alignmentDelta, null);
    }

    /**
     * @param subjectId what the event is about, or {@code null} if it is about nothing in particular
     * @param verb      what happened to the subject, or {@code null}
     */
    public GameEvent(EventType type, int turn, String description, String subjectId, int alignmentDelta,
                     EventVerb verb) {
        this.type = Objects.requireNonNull(type, "type");
        if (turn < 0) {
            throw new IllegalArgumentException("Turn must not be negative: " + turn);
        }
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Event description must not be blank");
        }
        this.turn = turn;
        this.timestamp = Instant.now();
        this.description = description;
        this.subjectId = subjectId;
        this.alignmentDelta = alignmentDelta;
        this.verb = verb;
    }

    public EventType getType() {
        return type;
    }

    public int getTurn() {
        return turn;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getDescription() {
        return description;
    }

    /** What the event is about, or {@code null}. */
    public String getSubjectId() {
        return subjectId;
    }

    public int getAlignmentDelta() {
        return alignmentDelta;
    }

    /** What happened to the subject, or {@code null}. */
    public EventVerb getVerb() {
        return verb;
    }

    @Override
    public String toString() {
        return "turn " + turn + " " + type + ": " + description;
    }
}
