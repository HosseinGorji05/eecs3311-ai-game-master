package engine.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Delivers every published {@link GameEvent} to all subscribed listeners, so the code that causes
 * an event does not need to know who reacts to it.
 *
 * <p>Pattern role: <b>Subject</b> of the Observer pattern. Listeners are notified synchronously in
 * the order they subscribed. A listener may publish further events from {@code onEvent()} (the
 * quest tracker does, SD05); those are delivered right away, and listeners that subscribe or
 * unsubscribe during a delivery take effect from the next event.
 */
public class GameEventBus {

    private final List<GameEventListener> listeners = new ArrayList<>();

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

    /** Calls {@code onEvent(event)} on every subscribed listener in turn. */
    public void publish(GameEvent event) {
        Objects.requireNonNull(event, "event");
        for (GameEventListener listener : List.copyOf(listeners)) {
            listener.onEvent(event);
        }
    }
}
