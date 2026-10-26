package engine.quests;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The quests the player has accepted, active and finished.
 *
 * <p>Invariant: {@code activeSideQuestCount() <= MAX_SIDE_QUESTS}; the main quest does not count.
 *
 * <p>{@code hasQuestFrom(NPC)} and {@code canOfferFrom(NPC)} of the design are added together with
 * NPCs.
 */
public class QuestLog {

    private static final int MAX_SIDE_QUESTS = 3;

    private final List<Quest> quests = new ArrayList<>();

    /**
     * Adds an active quest to the log.
     *
     * @return {@code false} if the quest is already in the log, is not active, or is a side quest
     *         while {@code MAX_SIDE_QUESTS} side quests are already active
     */
    public boolean accept(Quest quest) {
        Objects.requireNonNull(quest, "quest");
        if (quests.contains(quest) || quest.getStatus() != QuestStatus.ACTIVE) {
            return false;
        }
        if (!quest.isMain() && activeSideQuestCount() >= MAX_SIDE_QUESTS) {
            return false;
        }
        return quests.add(quest);
    }

    /** The active quests in the order they were accepted, as a new list. */
    public List<Quest> activeQuests() {
        return quests.stream().filter(quest -> quest.getStatus() == QuestStatus.ACTIVE).toList();
    }

    public int activeSideQuestCount() {
        return (int) quests.stream()
                .filter(quest -> !quest.isMain() && quest.getStatus() == QuestStatus.ACTIVE)
                .count();
    }
}
