package engine.events;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The game history: every published event in the order it happened, including rejected actions.
 *
 * <p>Pattern role: <b>ConcreteObserver</b> of the Observer pattern.
 */
public class EventLog implements GameEventListener {

    private final List<GameEvent> events = new ArrayList<>();

    @Override
    public void onEvent(GameEvent event) {
        append(Objects.requireNonNull(event, "event"));
    }

    /** The events of this type, oldest first. */
    public List<GameEvent> filter(EventType type) {
        Objects.requireNonNull(type, "type");
        return events.stream().filter(event -> event.getType() == type).toList();
    }

    /**
     * The last {@code count} events, oldest first (all of them if there are fewer).
     *
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public List<GameEvent> recent(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count must not be negative: " + count);
        }
        return List.copyOf(events.subList(Math.max(0, events.size() - count), events.size()));
    }

    private void append(GameEvent event) {
        events.add(event);
    }
}
