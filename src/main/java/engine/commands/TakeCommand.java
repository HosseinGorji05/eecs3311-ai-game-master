package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;
import engine.items.Inventory;
import engine.items.Item;
import engine.world.Location;

import java.util.Locale;
import java.util.Objects;

/**
 * Picks up an item lying in the current location and puts it into the player's inventory.
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. The weight check uses the item Composite: the
 * inventory's total weight counts everything inside nested containers (SD10).
 *
 * <p>The ITEM event of SD10, including the theft check on {@link Item#getOwnerId()}, is published
 * once the event bus exists.
 */
public class TakeCommand implements GameCommand {

    private final String itemName;

    public TakeCommand(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        if (itemName.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        this.itemName = itemName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.TAKE;
    }

    /** Invalid if the item is not in the current location or is too heavy to carry. */
    @Override
    public ValidationResult validate(GameState state) {
        Item item = state.currentLocation().findItem(itemName);
        if (item == null) {
            return new ValidationResult(false, "There is no " + itemName + " here.");
        }
        Inventory inventory = state.getPlayer().getInventory();
        if (!inventory.canCarry(item)) {
            return new ValidationResult(false, String.format(Locale.ROOT,
                    "The %s is too heavy (%s kg). You carry %s of %s kg.", item.getName(),
                    kg(item.getWeight()), kg(inventory.totalWeight()), kg(inventory.getMaxWeight())));
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Location location = state.currentLocation();
        Item item = location.removeItem(itemName);
        if (item == null) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        if (!state.getPlayer().getInventory().add(item)) {
            location.addItem(item);
            return new ActionResult(false, "You can't carry the " + item.getName() + ".");
        }
        return new ActionResult(true, "You take the " + item.getName() + ".");
    }

    /** Formats a weight with one decimal, dropping ".0" ("9.5", "10"). */
    private static String kg(double weight) {
        String text = String.format(Locale.ROOT, "%.1f", weight);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
