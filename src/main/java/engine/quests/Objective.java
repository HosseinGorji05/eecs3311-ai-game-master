package engine.quests;

import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;

import java.util.Objects;

/**
 * One goal of a quest, such as "pick up the iron key" or "visit the Wolf Den", with its progress.
 *
 * <p>An event matches when its type and verb fit the objective type and its subject is the
 * target (ignoring case): FETCH = ITEM / TOOK with the item name, DEFEAT = COMBAT / DEFEATED with
 * the enemy id, VISIT = MOVEMENT / MOVED with the location id.
 *
 * <p>Invariant: {@code 0 <= progress <= required}.
 */
public class Objective {

    private final ObjectiveType type;
    private final String targetId;
    private final int required;
    private int progress;

    /**
     * @param required how many matching events complete the objective (at least 1)
     */
    public Objective(ObjectiveType type, String targetId, int required) {
        this.type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(targetId, "targetId");
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("Objective target must not be blank");
        }
        if (required < 1) {
            throw new IllegalArgumentException("Required count must be at least 1: " + required);
        }
        this.targetId = targetId;
        this.required = required;
    }

    /** Whether this event counts towards the objective; never true once the objective is done. */
    public boolean matches(GameEvent event) {
        if (isDone() || !isAbout(event)) {
            return false;
        }
        return switch (type) {
            case FETCH -> event.getType() == EventType.ITEM && event.getVerb() == EventVerb.TOOK;
            case DEFEAT -> event.getType() == EventType.COMBAT && event.getVerb() == EventVerb.DEFEATED;
            case VISIT -> event.getType() == EventType.MOVEMENT && event.getVerb() == EventVerb.MOVED;
        };
    }

    /**
     * Whether this event makes the objective impossible: a FETCH objective is blocked when its item
     * is used up (ITEM / USED). DEFEAT and VISIT objectives are never blocked.
     */
    public boolean isBlockedBy(GameEvent event) {
        return type == ObjectiveType.FETCH && isAbout(event)
                && event.getType() == EventType.ITEM && event.getVerb() == EventVerb.USED;
    }

    /** Counts one more matching event, never beyond {@code required}. */
    public void advance() {
        progress = Math.min(required, progress + 1);
    }

    public boolean isDone() {
        return progress >= required;
    }

    public ObjectiveType getType() {
        return type;
    }

    public String getTargetId() {
        return targetId;
    }

    public int getRequired() {
        return required;
    }

    public int getProgress() {
        return progress;
    }

    private boolean isAbout(GameEvent event) {
        return event.getSubjectId() != null && event.getSubjectId().equalsIgnoreCase(targetId);
    }
}
