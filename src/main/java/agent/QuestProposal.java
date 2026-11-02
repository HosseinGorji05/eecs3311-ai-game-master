package agent;

import engine.quests.Objective;
import engine.quests.Reward;

import java.util.List;
import java.util.Objects;

/**
 * A side quest as the model (or a template) proposed it, before the engine has checked it (F07). It
 * is only data: nothing in it is executed, and {@link QuestValidator} must accept it before
 * {@code QuestGenerator} builds a {@code Quest} from it.
 *
 * <p>Besides the designed fields it keeps the giver the model named, if any, so a proposal claiming
 * to come from someone else can be rejected.
 */
public class QuestProposal {

    private final String title;
    private final String description;
    private final List<Objective> objectives;
    private final Reward reward;
    private final String giverName;

    /**
     * @param giverName the giver the model named, or {@code null} if it named none
     */
    public QuestProposal(String title, String description, List<Objective> objectives, Reward reward,
                         String giverName) {
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
        this.objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        this.reward = Objects.requireNonNull(reward, "reward");
        this.giverName = giverName;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /** The proposed objectives, as a read-only list. */
    public List<Objective> getObjectives() {
        return objectives;
    }

    public Reward getReward() {
        return reward;
    }

    /** The giver the model named, or {@code null}. */
    public String getGiverName() {
        return giverName;
    }
}
