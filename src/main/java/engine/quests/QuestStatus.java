package engine.quests;

/** The life cycle of a {@link Quest}: it starts ACTIVE and ends COMPLETED or FAILED, once. */
public enum QuestStatus {
    ACTIVE,
    COMPLETED,
    FAILED
}
