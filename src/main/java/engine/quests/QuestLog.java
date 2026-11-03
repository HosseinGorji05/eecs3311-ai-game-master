package engine.quests;

import engine.world.NPC;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The quests the player has accepted, active and finished.
 *
 * <p>Invariant: {@code activeSideQuestCount() <= MAX_SIDE_QUESTS}; the main quest does not count.
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

    /**
     * Replaces the log with these quests (active and finished, in the order they were accepted); used
     * when a saved game is loaded. Unlike {@link #accept}, finished quests are taken as they are.
     *
     * @throws IllegalArgumentException if a quest appears twice or more than {@code MAX_SIDE_QUESTS}
     *                                  side quests are active
     */
    public void restore(List<Quest> saved) {
        List<Quest> copy = List.copyOf(saved);
        if (copy.stream().distinct().count() != copy.size()
                || copy.stream().map(Quest::getId).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("A quest appears twice");
        }
        long active = copy.stream().filter(quest -> !quest.isMain() && quest.getStatus() == QuestStatus.ACTIVE).count();
        if (active > MAX_SIDE_QUESTS) {
            throw new IllegalArgumentException("More than " + MAX_SIDE_QUESTS + " side quests are active");
        }
        quests.clear();
        quests.addAll(copy);
    }

    /** The active quests in the order they were accepted, as a new list. */
    public List<Quest> activeQuests() {
        return quests.stream().filter(quest -> quest.getStatus() == QuestStatus.ACTIVE).toList();
    }

    /** Every quest in the log, active and finished, in the order they were accepted, as a new list. */
    public List<Quest> allQuests() {
        return List.copyOf(quests);
    }

    public int activeSideQuestCount() {
        return (int) quests.stream()
                .filter(quest -> !quest.isMain() && quest.getStatus() == QuestStatus.ACTIVE)
                .count();
    }

    /** Whether the player has an active quest from this NPC. */
    public boolean hasQuestFrom(NPC giver) {
        Objects.requireNonNull(giver, "giver");
        return quests.stream().anyMatch(quest -> quest.getStatus() == QuestStatus.ACTIVE
                && giver.getId().equals(quest.getGiverId()));
    }

    /**
     * Whether this NPC may offer a quest (SD07 n1): it is a quest giver, the player has no active quest
     * from it, and fewer than {@code MAX_SIDE_QUESTS} side quests are active.
     */
    public boolean canOfferFrom(NPC giver) {
        return giver.isQuestGiver() && !hasQuestFrom(giver) && activeSideQuestCount() < MAX_SIDE_QUESTS;
    }
}
