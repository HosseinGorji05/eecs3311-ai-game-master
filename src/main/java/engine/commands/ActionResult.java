package engine.commands;

import engine.events.GameEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The real outcome of one command: whether it succeeded, the message shown to the player and the
 * events it produced, which {@code RulesEngine} publishes.
 */
public class ActionResult {

    private final boolean success;
    private final String message;
    private final List<GameEvent> events = new ArrayList<>();

    public ActionResult(boolean success, String message) {
        this.success = success;
        this.message = Objects.requireNonNull(message, "message");
    }

    public void addEvent(GameEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    /** The events this command produced, in order, as a read-only view. */
    public List<GameEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
