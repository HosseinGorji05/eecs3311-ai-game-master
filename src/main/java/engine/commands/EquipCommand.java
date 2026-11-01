package engine.commands;

import engine.character.Player;
import engine.core.CombatMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Wields a carried weapon, so {@code Player.damage()} becomes its damage (F05).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. The weapon stays in the inventory and keeps counting
 * towards the weight; a weapon packed in a container is taken out to the top level first, so the
 * equipped weapon is never inside a container. A previously equipped weapon simply stays in the
 * inventory, unequipped. In combat the enemy takes its turn afterwards, like using an item.
 *
 * <p>A successful equip adds an ITEM event without a verb (subject = the weapon's name).
 */
public class EquipCommand implements GameCommand {

    private final String itemName;

    public EquipCommand(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        if (itemName.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        this.itemName = itemName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.EQUIP;
    }

    /** Invalid if the player does not carry the item, it is not a weapon, or it is already equipped. */
    @Override
    public ValidationResult validate(GameState state) {
        Player player = state.getPlayer();
        Item item = player.getInventory().find(itemName);
        if (item == null) {
            return DropCommand.notCarried(state, itemName);
        }
        if (!(item instanceof Weapon)) {
            return new ValidationResult(false, "You can't equip the " + item.getName() + ".");
        }
        if (item == player.getEquippedWeapon()) {
            return new ValidationResult(false, "You already wield the " + item.getName() + ".");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Player player = state.getPlayer();
        Inventory inventory = player.getInventory();
        if (!(inventory.find(itemName) instanceof Weapon weapon)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        List<String> messages = new ArrayList<>();
        Container holder = topLevelHolder(inventory, weapon);
        if (holder != null) {
            // Moving it inside the inventory keeps the total weight, so it always fits at the top level.
            inventory.remove(itemName);
            inventory.add(weapon);
            messages.add("You take the " + weapon.getName() + " out of the " + holder.getName() + ".");
        }
        Weapon previous = player.getEquippedWeapon();
        player.equip(weapon);
        messages.add("You equip the " + weapon.getName() + " (damage " + player.damage() + ")"
                + (previous != null ? " and put away the " + previous.getName() : "") + ".");
        List<GameEvent> events = new ArrayList<>();
        events.add(new GameEvent(EventType.ITEM, state.getTurn(), "equipped the " + weapon.getName(),
                weapon.getName(), 0));
        if (state.getMode() instanceof CombatMode combat) {
            AttackCommand.enemyTurn(combat.getEnemy(), state, rules, messages, events);
        }
        return AttackCommand.result(true, messages, events);
    }

    /** The top-level container the item is packed in, or {@code null} if it is at the top level. */
    private static Container topLevelHolder(Inventory inventory, Item item) {
        for (Item carried : inventory.getItems()) {
            if (carried instanceof Container container && container.contains(item)) {
                return container;
            }
        }
        return null;
    }
}
