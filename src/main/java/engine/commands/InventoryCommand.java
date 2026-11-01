package engine.commands;

import engine.character.Player;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Lists what the player carries, with the contents of every container, the equipped weapon and the
 * total weight, e.g.
 *
 * <pre>
 * Items: dagger (1 kg), pouch (0.7 kg: healing potion (0.5 kg))
 * Weapon: none (unarmed, damage 2)
 * You carry 1.7 of 10 kg.
 * </pre>
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Read-only: it is always valid, changes nothing and adds
 * no events (the turn still advances, as for every command, SD03).
 */
public class InventoryCommand implements GameCommand {

    @Override
    public ActionType getType() {
        return ActionType.INVENTORY;
    }

    @Override
    public ValidationResult validate(GameState state) {
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Player player = state.getPlayer();
        Inventory inventory = player.getInventory();
        Weapon weapon = player.getEquippedWeapon();
        String items = inventory.getItems().isEmpty() ? "none" : list(inventory.getItems());
        String wielded = weapon != null
                ? weapon.getName() + " (damage " + player.damage() + ")"
                : "none (unarmed, damage " + player.damage() + ")";
        return new ActionResult(true, "Items: " + items + "\nWeapon: " + wielded + "\nYou carry "
                + TakeCommand.kg(inventory.totalWeight()) + " of " + TakeCommand.kg(inventory.getMaxWeight()) + " kg.");
    }

    private static String list(List<Item> items) {
        return items.stream().map(InventoryCommand::describe).collect(Collectors.joining(", "));
    }

    /** "name (weight kg)", and for a container its contents after a colon ("empty" if none). */
    private static String describe(Item item) {
        String weight = TakeCommand.kg(item.getWeight()) + " kg";
        if (item instanceof Container container) {
            String contents = container.getContents().isEmpty() ? "empty" : list(container.getContents());
            return item.getName() + " (" + weight + ": " + contents + ")";
        }
        return item.getName() + " (" + weight + ")";
    }
}
