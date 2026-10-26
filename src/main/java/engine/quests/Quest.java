package engine.quests;

import java.util.List;
import java.util.Objects;

/**
 * A quest with 1-3 objectives and a reward. The main quest is hand-written; side quests come from
 * quest-giving NPCs.
 *
 * <p>A quest starts {@link QuestStatus#ACTIVE} and is completed or failed exactly once, so its
 * reward can never be granted twice.
 */
public class Quest {

    private static final int MAX_OBJECTIVES = 3;

    private final String id;
    private final String title;
    private final String description;
    private final String giverId;
    private final boolean main;
    private QuestStatus status = QuestStatus.ACTIVE;
    private final List<Objective> objectives;
    private final Reward reward;

    /**
     * @param giverId the id of the NPC who gave the quest, or {@code null} if nobody did
     * @throws IllegalArgumentException if there are not 1-3 objectives, or a text is blank
     */
    public Quest(String id, String title, String description, String giverId, boolean main,
                 List<Objective> objectives, Reward reward) {
        this.id = requireText(id, "id");
        this.title = requireText(title, "title");
        this.description = requireText(description, "description");
        this.giverId = giverId;
        this.main = main;
        Objects.requireNonNull(objectives, "objectives");
        if (objectives.isEmpty() || objectives.size() > MAX_OBJECTIVES) {
            throw new IllegalArgumentException("A quest needs 1-" + MAX_OBJECTIVES + " objectives: " + objectives.size());
        }
        this.objectives = List.copyOf(objectives);
        this.reward = Objects.requireNonNull(reward, "reward");
    }

    /** The objectives, as a read-only list. */
    public List<Objective> getObjectives() {
        return objectives;
    }

    public Reward getReward() {
        return reward;
    }

    /** Whether every objective is done (the quest may still need {@link #complete()}). */
    public boolean isComplete() {
        return objectives.stream().allMatch(Objective::isDone);
    }

    /** @throws IllegalStateException if the quest is not active */
    public void complete() {
        finish(QuestStatus.COMPLETED);
    }

    /** @throws IllegalStateException if the quest is not active */
    public void fail() {
        finish(QuestStatus.FAILED);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /** The id of the NPC who gave the quest, or {@code null}. */
    public String getGiverId() {
        return giverId;
    }

    public boolean isMain() {
        return main;
    }

    public QuestStatus getStatus() {
        return status;
    }

    private void finish(QuestStatus outcome) {
        if (status != QuestStatus.ACTIVE) {
            throw new IllegalStateException("Quest " + id + " is already " + status);
        }
        status = outcome;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Quest " + field + " must not be blank");
        }
        return value;
    }
}
