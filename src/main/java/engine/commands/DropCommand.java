package engine.commands;

import engine.character.Player;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.world.Location;

import java.util.Objects;

/**
 * Takes an item out of the inventory, even from inside a container, and leaves it lying in the
 * current location (F05). A dropped container keeps its contents.
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Dropping the equipped weapon (or a container holding
 * it) unequips it. A successful drop adds an ITEM event without a verb (subject = the item's
 * name), so it never counts towards a FETCH objective.
 */
public class DropCommand implements GameCommand {

    private final String itemName;

    public DropCommand(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        if (itemName.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        this.itemName = itemName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.DROP;
    }

    /** Invalid if the player does not carry the item. */
    @Override
    public ValidationResult validate(GameState state) {
        if (state.getPlayer().getInventory().find(itemName) == null) {
            return notCarried(state, itemName);
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Inventory inventory = state.getPlayer().getInventory();
        Item item = inventory.remove(itemName);
        if (item == null) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        Location location = state.currentLocation();
        location.addItem(item);
        ActionResult result = new ActionResult(true, "You drop the " + item.getName() + "." + unequipIfInside(state, item));
        result.addEvent(new GameEvent(EventType.ITEM, state.getTurn(), "dropped the " + item.getName() + " in "
                + location.getName(), item.getName(), 0));
        return result;
    }

    /**
     * The rejection for an item the player does not carry, with a hint when it lies in the current
     * location.
     */
    static ValidationResult notCarried(GameState state, String itemName) {
        String hint = state.currentLocation().findItem(itemName) != null ? " It is here: take it first." : "";
        return new ValidationResult(false, "You don't carry the " + itemName + "." + hint);
    }

    /**
     * Unequips the player's weapon if it is {@code item} or inside it, for an item that leaves the
     * player's hands.
     *
     * @return the sentence telling the player, starting with a space, or {@code ""} if nothing changed
     */
    static String unequipIfInside(GameState state, Item item) {
        Player player = state.getPlayer();
        Item weapon = player.getEquippedWeapon();
        if (weapon == null) {
            return "";
        }
        if (weapon == item) {
            player.unequip();
            return " You no longer wield it.";
        }
        if (item instanceof Container container && container.contains(weapon)) {
            player.unequip();
            return " You no longer wield the " + weapon.getName() + ".";
        }
        return "";
    }
}
