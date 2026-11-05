package engine.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Delivers every published {@link GameEvent} to all subscribed listeners, so the code that causes
 * an event does not need to know who reacts to it.
 *
 * <p>Pattern role: <b>Subject</b> of the Observer pattern. Listeners are notified synchronously in
 * the order they subscribed. A listener may publish further events from {@code onEvent()} (the
 * quest tracker does, SD05); those are delivered right away, and listeners that subscribe or
 * unsubscribe during a delivery take effect from the next event.
 *
 * <p>A listener that throws does not stop the others or the turn: the bus reports the failure once (one
 * line naming the listener, the event and the exception) and goes on with the next listener. By default
 * the line goes to the standard error stream of the moment; the player is not shown it.
 */
public class GameEventBus {

    private final List<GameEventListener> listeners = new ArrayList<>();
    private final Consumer<String> failureReport;

    /** A bus that reports a failing listener on standard error. */
    public GameEventBus() {
        this(line -> System.err.println(line));
    }

    /** A bus that reports each failing listener with one line to {@code failureReport} (e.g. for tests). */
    public GameEventBus(Consumer<String> failureReport) {
        this.failureReport = Objects.requireNonNull(failureReport, "failureReport");
    }

    /** Adds a listener; subscribing the same listener twice has no effect. */
    public void subscribe(GameEventListener listener) {
        Objects.requireNonNull(listener, "listener");
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(GameEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Calls {@code onEvent(event)} on every subscribed listener in turn; a listener that throws is
     * reported once and the next one is called.
     */
    public void publish(GameEvent event) {
        Objects.requireNonNull(event, "event");
        for (GameEventListener listener : List.copyOf(listeners)) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException failure) {
                String name = listener.getClass().getSimpleName().isEmpty()
                        ? listener.getClass().getName() : listener.getClass().getSimpleName();
                failureReport.accept("[event bus] " + name + " failed on the "
                        + event.getType() + " event of turn " + event.getTurn() + " (\"" + event.getDescription()
                        + "\"): " + failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            }
        }
    }
}
