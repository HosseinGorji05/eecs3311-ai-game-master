package engine.events;

/** The categories of {@link GameEvent}, also used to filter the event history. */
public enum EventType {
    MOVEMENT,
    ITEM,
    COMBAT,
    DIALOGUE,
    QUEST,
    REJECTED
}
