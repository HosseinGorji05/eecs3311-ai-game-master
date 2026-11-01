package engine.commands;

import engine.character.CharacterClass;
import engine.core.CombatMode;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Container;
import engine.items.Inventory;
import engine.quests.Quest;
import engine.quests.QuestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UseItemCommandTest {

    @Test
    void typeIsUse() {
        assertEquals(ActionType.USE, new UseItemCommand("healing potion").getType());
    }

    @Test
    void aPotionHealsButNeverAboveTheMaximumAndIsUsedUp() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Inventory inventory = game.ari.getInventory();
        game.ari.takeDamage(5);
        double weight = inventory.totalWeight();

        ActionResult result = game.run(new UseItemCommand("Healing Potion"));

        assertTrue(result.isSuccess());
        assertEquals("You use the healing potion and recover 5 HP (30/30 HP).", result.getMessage());
        assertEquals(30, game.ari.getHp());
        assertNull(inventory.find("healing potion"));
        assertEquals(weight - 0.5, inventory.totalWeight(), 1e-9);
        GameEvent used = game.published.get(0);
        assertEquals(EventType.ITEM, used.getType());
        assertEquals(EventVerb.USED, used.getVerb());
        assertEquals("healing potion", used.getSubjectId());
        assertEquals("used the healing potion", used.getDescription());
        assertEquals(game.published, result.getEvents());
    }

    @Test
    void aPotionHealsItsFullAmountWhenThereIsRoom() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.ari.takeDamage(15);

        ActionResult result = game.run(new UseItemCommand("healing potion"));

        assertEquals("You use the healing potion and recover 10 HP (25/30 HP).", result.getMessage());
        assertEquals(25, game.ari.getHp());
    }

    @Test
    void breadFromTheTavernCanBeEaten() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.run(new TakeCommand("bread"));
        game.ari.takeDamage(3);

        ActionResult result = game.run(new UseItemCommand("bread"));

        assertEquals("You use the bread and recover 2 HP (29/30 HP).", result.getMessage());
        assertNull(game.ari.getInventory().find("bread"));
    }

    @Test
    void aPotionInsideAContainerCanBeUsed() {
        CommandTestGame game = new CommandTestGame(CharacterClass.ROGUE, "village-square");
        Container pouch = (Container) game.ari.getInventory().find("pouch");
        game.ari.takeDamage(4);

        game.run(new UseItemCommand("healing potion"));

        assertEquals(24, game.ari.getHp());
        assertTrue(pouch.getContents().isEmpty());
    }

    @Test
    void inCombatTheEnemyTakesItsTurnAfterwards() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf");
        game.ari.takeDamage(12);

        ActionResult result = game.run(new UseItemCommand("healing potion"), 12);   // 12 + 3 = 15 >= 13: hit

        assertEquals("You use the healing potion and recover 10 HP (28/30 HP).\n"
                + "wolf attacks Ari: rolls 12 + 3 = 15 against defense 13. Hit for 3 damage (Ari: 25/30 HP).",
                result.getMessage());
        assertEquals(25, game.ari.getHp());
        assertInstanceOf(CombatMode.class, game.state.getMode());
        assertEquals(List.of(EventType.ITEM, EventType.COMBAT), game.publishedTypes());
    }

    @Test
    void usingTheLastItemAFetchObjectiveNeedsFailsTheQuest() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        Quest quest = game.fetchQuest("healing potion");
        game.ari.takeDamage(5);

        game.run(new UseItemCommand("healing potion"));

        assertEquals(QuestStatus.FAILED, quest.getStatus());
        assertEquals(List.of(EventType.ITEM, EventType.QUEST), game.publishedTypes());
        assertEquals(EventVerb.FAILED, game.published.get(1).getVerb());
    }

    @Test
    void usingOneOfTwoCopiesDoesNotFailTheQuestButTheLastOneDoes() {
        CommandTestGame game = new CommandTestGame(CharacterClass.MAGE, "village-square");   // two potions
        Quest quest = game.fetchQuest("healing potion");
        game.ari.takeDamage(15);

        game.run(new UseItemCommand("healing potion"));

        assertNull(game.published.get(0).getVerb(), "another healing potion is still carried");
        assertEquals(QuestStatus.ACTIVE, quest.getStatus());
        assertNotNull(game.ari.getInventory().find("healing potion"));

        game.run(new UseItemCommand("healing potion"));

        assertEquals(EventVerb.USED, game.published.get(1).getVerb());
        assertEquals(QuestStatus.FAILED, quest.getStatus());
    }

    @Test
    void atFullHealthTheItemIsKept() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.assertRejectedWithoutChange(new UseItemCommand("healing potion"), "You are already at full health (30/30 HP).");
    }

    @Test
    void onlyAConsumableCanBeUsed() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "village-square");
        game.ari.takeDamage(5);
        game.assertRejectedWithoutChange(new UseItemCommand("sword"), "You can't use the sword.");
    }

    @Test
    void anItemThePlayerDoesNotCarryIsRejected() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "old-mill");
        game.ari.takeDamage(5);
        game.assertRejectedWithoutChange(new UseItemCommand("healing herbs"),
                "You don't carry the healing herbs. It is here: take it first.");
        game.assertRejectedWithoutChange(new UseItemCommand("elixir"), "You don't carry the elixir.");
    }

    @Test
    void aRejectedUseInCombatGivesTheEnemyNoTurn() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "wolf-den");
        game.fight("wolf");
        game.assertRejectedWithoutChange(new UseItemCommand("healing potion"), "You are already at full health (30/30 HP).");
    }
}
