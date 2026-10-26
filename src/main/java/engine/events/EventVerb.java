package engine.events;

/**
 * What happened to an event's subject, telling apart events of the same {@link EventType} (e.g.
 * an ITEM event for a potion that was taken versus one that was used up).
 */
public enum EventVerb {
    /** An item was picked up (also when it was stolen). */
    TOOK,
    /** An item was used up and no longer exists. */
    USED,
    /** The player moved into a location. */
    MOVED,
    /** An enemy was defeated. */
    DEFEATED,
    /** A quest was completed. */
    COMPLETED,
    /** A quest failed. */
    FAILED
}
