package presentation;

import engine.quests.Objective;
import engine.quests.Quest;
import engine.quests.QuestLog;
import engine.quests.QuestStatus;
import engine.world.NPC;
import engine.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The GUI's quest log panel content (F08) without a display: the quests in the order shown (active ones
 * first, the main quest at the top, then completed, then failed) and, for each, its text read at the
 * moment of the copy: title, status and giver, every objective with a check box and its progress, and
 * the reward.
 *
 * <p>The panel's {@code JList<Quest>} holds the quests, but its renderer only reads the copied
 * {@link Entry}s, never the live quests, which change while a turn runs.
 */
final class QuestListModel {

    /**
     * One quest as shown.
     *
     * @param status     "main quest, active" or "completed, from Brann"
     * @param objectives "[x] defeat wolf 1/1", "[ ] fetch bread 0/1"
     */
    record Entry(String title, String status, List<String> objectives, String reward) {
        Entry {
            objectives = List.copyOf(objectives);
        }

        /** The entry as HTML for a list cell; every text is escaped (quest titles may come from the model). */
        String toHtml() {
            StringBuilder html = new StringBuilder("<html><b>").append(GuiText.escapeHtml(title)).append("</b> (")
                    .append(GuiText.escapeHtml(status)).append(')');
            for (String objective : objectives) {
                html.append("<br>&nbsp;&nbsp;").append(GuiText.escapeHtml(objective));
            }
            return html.append("<br>&nbsp;&nbsp;Reward: ").append(GuiText.escapeHtml(reward)).append("</html>").toString();
        }
    }

    private final List<Quest> quests;
    private final Map<Quest, Entry> entries;

    private QuestListModel(List<Quest> quests, Map<Quest, Entry> entries) {
        this.quests = List.copyOf(quests);
        this.entries = entries;
    }

    static final QuestListModel EMPTY = new QuestListModel(List.of(), new IdentityHashMap<>());

    /** Copies the quest log: the quests in display order and their text now. */
    static QuestListModel of(QuestLog log, World world) {
        Objects.requireNonNull(log, "log");
        List<Quest> ordered = new ArrayList<>(log.allQuests());
        ordered.sort(Comparator.comparingInt(QuestListModel::rank));
        Map<Quest, Entry> entries = new IdentityHashMap<>();
        for (Quest quest : ordered) {
            entries.put(quest, entry(quest, world));
        }
        return new QuestListModel(ordered, entries);
    }

    /** The quests in display order. */
    List<Quest> getQuests() {
        return quests;
    }

    /** The copied text of a quest of this model, or {@code null} for another quest. */
    Entry entryOf(Quest quest) {
        return entries.get(quest);
    }

    static Entry entry(Quest quest, World world) {
        String status = quest.getStatus().name().toLowerCase(Locale.ROOT);
        List<String> objectives = new ArrayList<>();
        for (Objective objective : quest.getObjectives()) {
            objectives.add(objectiveText(objective));
        }
        return new Entry(quest.getTitle(), quest.isMain() ? "main quest, " + status
                : status + ", from " + giverName(quest, world), objectives, quest.getReward().describe());
    }

    /** "[x] defeat wolf 1/1" once done, "[ ] fetch bread 0/1" before. */
    static String objectiveText(Objective objective) {
        return (objective.isDone() ? "[x] " : "[ ] ") + objective.getType().name().toLowerCase(Locale.ROOT) + " "
                + objective.getTargetId() + " " + objective.getProgress() + "/" + objective.getRequired();
    }

    /** Main active quest 0, other active 1, completed 2, failed 3 (the sort is stable within a rank). */
    private static int rank(Quest quest) {
        if (quest.getStatus() == QuestStatus.ACTIVE) {
            return quest.isMain() ? 0 : 1;
        }
        return quest.getStatus() == QuestStatus.COMPLETED ? 2 : 3;
    }

    /** The giver's name, its id if it is not in the world, or "nobody" (the main quest). */
    static String giverName(Quest quest, World world) {
        if (quest.getGiverId() == null) {
            return "nobody";
        }
        NPC giver = world == null ? null : world.findNpc(quest.getGiverId());
        return giver != null ? giver.getName() : quest.getGiverId();
    }
}
