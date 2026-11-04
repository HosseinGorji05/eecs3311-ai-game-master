package presentation;

import engine.character.Player;
import engine.core.GameState;
import engine.items.Inventory;

import javax.swing.tree.DefaultTreeModel;
import java.util.List;
import java.util.Objects;

/**
 * Everything the GUI's panels show, copied from the game state on the thread that runs the controller
 * and then handed to the Swing thread, which never reads the live state. A new copy is taken whenever
 * the controller renders or an event arrives (SD11: {@code onEvent} calls {@code refresh()}).
 *
 * <p>The tree model is built here but only touched by the Swing thread once it has been handed over.
 */
final class GuiState {

    private final String statusLine;
    private final String modeLine;
    private final String prompt;
    private final MapModel map;
    private final DefaultTreeModel inventory;
    private final double weight;
    private final double maxWeight;
    private final List<InventoryTreeModel.ItemNode> itemsHere;
    private final QuestListModel quests;
    private final ControlRules rules;

    private GuiState(String statusLine, String modeLine, String prompt, MapModel map, DefaultTreeModel inventory,
                     double weight, double maxWeight, List<InventoryTreeModel.ItemNode> itemsHere,
                     QuestListModel quests, ControlRules rules) {
        this.statusLine = statusLine;
        this.modeLine = modeLine;
        this.prompt = prompt;
        this.map = map;
        this.inventory = inventory;
        this.weight = weight;
        this.maxWeight = maxWeight;
        this.itemsHere = List.copyOf(itemsHere);
        this.quests = quests;
        this.rules = rules;
    }

    /** The empty panels before a game starts. */
    static GuiState empty() {
        return new GuiState(GuiText.NO_GAME, "", GuiText.COMMAND_PROMPT, MapModel.EMPTY, InventoryTreeModel.empty(),
                0, Inventory.MAX_CARRY_WEIGHT, List.of(), QuestListModel.EMPTY, ControlRules.NOT_STARTED);
    }

    /** Copies the state; the empty panels if no game has started. */
    static GuiState of(GameState state) {
        Objects.requireNonNull(state, "state");
        Player player = state.getPlayer();
        if (player == null || state.getWorld() == null || state.getMode() == null) {
            return empty();
        }
        return new GuiState(GuiText.statusLine(state), GuiText.modeLine(state), GuiText.prompt(state),
                MapModel.of(state), InventoryTreeModel.build(player.getInventory(), player.getEquippedWeapon()),
                player.getInventory().totalWeight(), player.getInventory().getMaxWeight(),
                InventoryTreeModel.itemsHere(state), QuestListModel.of(state.getQuestLog(), state.getWorld()),
                ControlRules.of(state));
    }

    String getStatusLine() {
        return statusLine;
    }

    String getModeLine() {
        return modeLine;
    }

    String getPrompt() {
        return prompt;
    }

    MapModel getMap() {
        return map;
    }

    DefaultTreeModel getInventory() {
        return inventory;
    }

    double getWeight() {
        return weight;
    }

    double getMaxWeight() {
        return maxWeight;
    }

    List<InventoryTreeModel.ItemNode> getItemsHere() {
        return itemsHere;
    }

    QuestListModel getQuests() {
        return quests;
    }

    ControlRules getRules() {
        return rules;
    }
}
