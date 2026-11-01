package engine.commands;

import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;

import java.util.Objects;

/**
 * Moves a carried item into a container the player carries, such as a potion into the pouch (F05).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Both the item and the container come from the inventory
 * (UC05: "inventory for the others"), at any depth. The item stays carried, so the total weight,
 * which the Composite counts through every nested container, does not change and the weight limit
 * always holds; what can fail is the container's capacity, and a container can never go into itself
 * or into a container it holds. Packing the equipped weapon unequips it. A successful put adds an
 * ITEM event without a verb (subject = the item's name), so it never counts towards a FETCH
 * objective.
 */
public class PutCommand implements GameCommand {

    private final String itemName;
    private final String containerName;

    public PutCommand(String itemName, String containerName) {
        Objects.requireNonNull(itemName, "itemName");
        Objects.requireNonNull(containerName, "containerName");
        if (itemName.isBlank() || containerName.isBlank()) {
            throw new IllegalArgumentException("Item and container names must not be blank");
        }
        this.itemName = itemName.trim();
        this.containerName = containerName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.PUT;
    }

    /**
     * Invalid if the player does not carry the item or the container, the container is not a
     * container, the item is the container or holds it, the item is already directly in it, or the
     * container is full.
     */
    @Override
    public ValidationResult validate(GameState state) {
        Inventory inventory = state.getPlayer().getInventory();
        Item item = inventory.find(itemName);
        if (item == null) {
            return DropCommand.notCarried(state, itemName);
        }
        Item target = inventory.find(containerName);
        if (target == null) {
            return DropCommand.notCarried(state, containerName);
        }
        if (!(target instanceof Container container)) {
            return new ValidationResult(false, "The " + target.getName() + " is not a container.");
        }
        if (item == container) {
            return new ValidationResult(false, "You can't put the " + item.getName() + " into itself.");
        }
        if (item instanceof Container held && held.contains(container)) {
            return new ValidationResult(false, "You can't put the " + item.getName() + " into the "
                    + container.getName() + ": the " + container.getName() + " is inside it.");
        }
        if (container.getContents().contains(item)) {
            return new ValidationResult(false, "The " + item.getName() + " is already in the " + container.getName() + ".");
        }
        if (container.getContents().size() >= container.getCapacity()) {
            return new ValidationResult(false, "The " + container.getName() + " is full ("
                    + container.getCapacity() + " items).");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Inventory inventory = state.getPlayer().getInventory();
        if (!(inventory.find(containerName) instanceof Container container)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        Item item = inventory.remove(itemName);
        if (item == null || !container.add(item)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        ActionResult result = new ActionResult(true, "You put the " + item.getName() + " in the "
                + container.getName() + "." + DropCommand.unequipIfInside(state, item));
        result.addEvent(new GameEvent(EventType.ITEM, state.getTurn(),
                "put the " + item.getName() + " in the " + container.getName(), item.getName(), 0));
        return result;
    }
}
