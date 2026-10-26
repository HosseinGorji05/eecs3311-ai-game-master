package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
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
 * <p>A successful take adds an ITEM event (verb TOOK, subject = the item's name) to its result,
 * which {@code RulesEngine} publishes.
 *
 * <p><b>Theft (SD10 n4).</b> The design records a take as a theft, with alignment delta
 * {@value #THEFT_ALIGNMENT_DELTA}, when the item belongs to an NPC ({@link Item#getOwnerId()} is
 * set) who is not handing it over in a conversation. <b>Temporary rule:</b> NPCs and dialogue mode
 * do not exist yet, so nobody can hand an item over, and taking <em>any</em> item with an
 * {@code ownerId} is a theft. Once dialogue mode exists, a take while talking to the owner will
 * not be a theft.
 */
public class TakeCommand implements GameCommand {

    /** Alignment delta of taking an item that belongs to an NPC (Stage 1 tuning numbers). */
    static final int THEFT_ALIGNMENT_DELTA = -2;

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
        String owner = item.getOwnerId();
        boolean theft = owner != null;
        ActionResult result = new ActionResult(true, theft
                ? "You take the " + item.getName() + ". It belongs to " + owner + "."
                : "You take the " + item.getName() + ".");
        result.addEvent(new GameEvent(EventType.ITEM, state.getTurn(),
                theft ? "stole the " + item.getName() + " from " + owner : "took the " + item.getName(),
                item.getName(), theft ? THEFT_ALIGNMENT_DELTA : 0, EventVerb.TOOK));
        return result;
    }

    /** Formats a weight with one decimal, dropping ".0" ("9.5", "10"). */
    private static String kg(double weight) {
        String text = String.format(Locale.ROOT, "%.1f", weight);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
