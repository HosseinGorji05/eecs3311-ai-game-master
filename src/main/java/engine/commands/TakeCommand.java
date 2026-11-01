package engine.commands;

import engine.core.DialogueMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Inventory;
import engine.items.Item;
import engine.world.Location;
import engine.world.NPC;

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
 * <p><b>Theft and handover (SD10 n4, F03).</b> Outside a conversation, taking an item that belongs
 * to an NPC ({@link Item#getOwnerId()} is set) is a theft, with alignment delta
 * {@value #THEFT_ALIGNMENT_DELTA}. In {@link DialogueMode} a take is the NPC handing an item over (the
 * reply's side effect, SD07): it is valid only for an item that NPC owns, is not a theft, and the
 * item stops belonging to the NPC.
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

    /**
     * Invalid if the item is not in the current location or is too heavy to carry, and in a
     * conversation also if the NPC does not own it.
     */
    @Override
    public ValidationResult validate(GameState state) {
        Item item = state.currentLocation().findItem(itemName);
        if (item == null) {
            return new ValidationResult(false, "There is no " + itemName + " here.");
        }
        if (state.getMode() instanceof DialogueMode dialogue && !ownedBy(item, dialogue.getNpc())) {
            return new ValidationResult(false, "The " + item.getName() + " is not "
                    + dialogue.getNpc().getName() + "'s to give.");
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
        if (state.getMode() instanceof DialogueMode dialogue) {
            item.clearOwner();
            String giver = dialogue.getNpc().getName();
            ActionResult result = new ActionResult(true, giver + " hands you the " + item.getName() + ".");
            result.addEvent(new GameEvent(EventType.ITEM, state.getTurn(),
                    "received the " + item.getName() + " from " + giver, item.getName(), 0, EventVerb.TOOK));
            return result;
        }
        String owner = ownerName(location, item.getOwnerId());
        boolean theft = owner != null;
        ActionResult result = new ActionResult(true, theft
                ? "You take the " + item.getName() + ". It belongs to " + owner + "."
                : "You take the " + item.getName() + ".");
        result.addEvent(new GameEvent(EventType.ITEM, state.getTurn(),
                theft ? "stole the " + item.getName() + " from " + owner : "took the " + item.getName(),
                item.getName(), theft ? THEFT_ALIGNMENT_DELTA : 0, EventVerb.TOOK));
        return result;
    }

    private static boolean ownedBy(Item item, NPC npc) {
        return npc.getId().equals(item.getOwnerId());
    }

    /** The owner's name if the owner is here, else the owner id; {@code null} for an item nobody owns. */
    private static String ownerName(Location location, String ownerId) {
        if (ownerId == null) {
            return null;
        }
        NPC owner = location.findNpc(ownerId);
        return owner != null ? owner.getName() : ownerId;
    }

    /** Formats a weight with one decimal, dropping ".0" ("9.5", "10"); shared by the item commands. */
    static String kg(double weight) {
        String text = String.format(Locale.ROOT, "%.1f", weight);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
