package presentation;

import engine.core.DialogueMode;
import engine.core.GameState;
import engine.quests.Objective;
import engine.quests.Quest;
import engine.world.NPC;
import engine.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the dialogue panel shows (F03, SD07), copied from the game state without a display: whether the
 * player is in a conversation and with whom. {@code conversation} is the {@link DialogueMode} itself, used
 * only to tell one conversation from the next (it is compared, never read), so the panel starts a fresh
 * conversation when the mode changes, also after a load.
 *
 * <p>The nested {@link QuestCard} is an offer as the card shows it (F07) and {@link Person} an NPC the player
 * can click to talk to (F03: "clicking an NPC").
 */
record DialogueState(boolean talking, Object conversation, String npcName, String npcRole) {

    static final DialogueState NONE = new DialogueState(false, null, null, null);

    /** The quest offer card: who offers it, the title, description, objectives and reward. */
    record QuestCard(String giver, String title, String description, List<String> objectives, String reward) {
        QuestCard {
            objectives = List.copyOf(objectives);
        }

        /** Copies the offer (on the thread that may read the quest and the world). */
        static QuestCard of(Quest quest, World world) {
            List<String> objectives = new ArrayList<>();
            for (Objective objective : quest.getObjectives()) {
                objectives.add(capitalize(objective.getType().name().toLowerCase(Locale.ROOT)) + " "
                        + objective.getTargetId() + (objective.getRequired() > 1 ? " (" + objective.getRequired() + ")" : ""));
            }
            return new QuestCard(QuestListModel.giverName(quest, world), quest.getTitle(), quest.getDescription(),
                    objectives, quest.getReward().describe());
        }

        /** The card as HTML; every text is escaped (the quest may come from the model). */
        String toHtml() {
            StringBuilder html = new StringBuilder("<html><b>").append(GuiText.escapeHtml(title)).append("</b><br>")
                    .append(GuiText.escapeHtml(description)).append("<br><br>Objectives:");
            for (String objective : objectives) {
                html.append("<br>&bull; ").append(GuiText.escapeHtml(objective));
            }
            return html.append("<br><br>Reward: ").append(GuiText.escapeHtml(reward)).append("</html>").toString();
        }
    }

    /** An NPC in the current location: the name a talk proposal names, and the label ("Marta (tavern keeper)"). */
    record Person(String name, String label) {
    }

    static DialogueState of(GameState state) {
        Objects.requireNonNull(state, "state");
        if (!(state.getMode() instanceof DialogueMode dialogue)) {
            return NONE;
        }
        NPC npc = dialogue.getNpc();
        return new DialogueState(true, dialogue, npc.getName(), npc.getRole());
    }

    /** The NPCs in the current location, in order; empty before a game. */
    static List<Person> peopleHere(GameState state) {
        Objects.requireNonNull(state, "state");
        if (state.currentLocation() == null) {
            return List.of();
        }
        return state.currentLocation().getNpcs().stream().map(npc -> new Person(npc.getName(), npc.describe())).toList();
    }

    /** "Marta, the tavern keeper", the panel's heading; "" outside a conversation. */
    String heading() {
        return talking ? npcName + ", the " + npcRole : "";
    }

    /** The portrait's letter: the first letter of the NPC's name. */
    String initial() {
        return talking ? npcName.substring(0, 1).toUpperCase(Locale.ROOT) : "";
    }

    private static String capitalize(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
