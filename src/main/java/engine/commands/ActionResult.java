package engine.commands;

import java.util.Objects;

/**
 * The real outcome of one command: whether it succeeded and the message shown to the player.
 *
 * <p>The events of the design ({@code events}, {@code addEvent()}, {@code getEvents()}) are added
 * together with the event bus.
 */
public class ActionResult {

    private final boolean success;
    private final String message;

    public ActionResult(boolean success, String message) {
        this.success = success;
        this.message = Objects.requireNonNull(message, "message");
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
