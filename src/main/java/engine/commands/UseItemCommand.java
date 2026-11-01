package engine.commands;

import engine.character.Player;
import engine.core.CombatMode;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Consumable;
import engine.items.Inventory;
import engine.items.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Uses up a carried consumable, such as a healing potion or bread, restoring its HP but never above
 * the maximum (F05).
 *
 * <p>Pattern role: <b>ConcreteCommand</b>. Only a {@link Consumable} can be used; a weapon is
 * equipped, and a key opens its door when the player goes through it. In combat the enemy takes its
 * turn afterwards (F04).
 *
 * <p>Events: an ITEM event (subject = the item's name) whose verb is USED when the player carries no
 * other item of that name, so a FETCH objective for it is blocked only once none is left; an
 * earlier copy gets an ITEM event without a verb. In combat the enemy's attack adds COMBAT events.
 */
public class UseItemCommand implements GameCommand {

    private final String itemName;

    public UseItemCommand(String itemName) {
        Objects.requireNonNull(itemName, "itemName");
        if (itemName.isBlank()) {
            throw new IllegalArgumentException("Item name must not be blank");
        }
        this.itemName = itemName.trim();
    }

    @Override
    public ActionType getType() {
        return ActionType.USE;
    }

    /**
     * Invalid if the player does not carry the item, it is not a consumable, or the player is at
     * full HP (so the item is not wasted).
     */
    @Override
    public ValidationResult validate(GameState state) {
        Player player = state.getPlayer();
        Item item = player.getInventory().find(itemName);
        if (item == null) {
            return DropCommand.notCarried(state, itemName);
        }
        if (!(item instanceof Consumable)) {
            return new ValidationResult(false, "You can't use the " + item.getName() + ".");
        }
        if (player.getHp() >= player.getMaxHp()) {
            return new ValidationResult(false, "You are already at full health (" + player.getHp() + "/"
                    + player.getMaxHp() + " HP).");
        }
        return new ValidationResult(true, null);
    }

    @Override
    public ActionResult execute(GameState state, RulesEngine rules) {
        Player player = state.getPlayer();
        Inventory inventory = player.getInventory();
        if (!(inventory.find(itemName) instanceof Consumable)) {
            throw new IllegalStateException("validate() must succeed before execute()");
        }
        Consumable consumable = (Consumable) inventory.remove(itemName);
        int before = player.getHp();
        player.heal(consumable.getHealAmount());
        List<String> messages = new ArrayList<>();
        List<GameEvent> events = new ArrayList<>();
        messages.add("You use the " + consumable.getName() + " and recover " + (player.getHp() - before) + " HP ("
                + player.getHp() + "/" + player.getMaxHp() + " HP).");
        EventVerb verb = inventory.find(consumable.getName()) == null ? EventVerb.USED : null;
        events.add(new GameEvent(EventType.ITEM, state.getTurn(), "used the " + consumable.getName(),
                consumable.getName(), 0, verb));
        if (state.getMode() instanceof CombatMode combat) {
            AttackCommand.enemyTurn(combat.getEnemy(), state, rules, messages, events);
        }
        return AttackCommand.result(true, messages, events);
    }
}
