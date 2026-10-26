package engine.events;

/**
 * Something that reacts to game events.
 *
 * <p>Pattern role: <b>Observer</b> of the Observer pattern, notified by {@link GameEventBus}.
 */
public interface GameEventListener {

    void onEvent(GameEvent event);
}
