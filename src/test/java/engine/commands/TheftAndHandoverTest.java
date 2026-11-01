package engine.commands;

import engine.character.CharacterClass;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.items.Item;
import engine.items.KeyItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The theft rule (SD10 n4, F12): taking an NPC's item is a theft (alignment -2) unless the NPC hands
 * it over in a conversation, which costs nothing and makes the item the player's.
 */
class TheftAndHandoverTest {

    private static int alignment(CommandTestGame game) {
        return game.state.getEventLog().recent(Integer.MAX_VALUE).stream().mapToInt(GameEvent::getAlignmentDelta).sum();
    }

    @Test
    void takingTheBlacksmithsKeyWithoutAskingIsATheft() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");

        ActionResult result = game.run(new TakeCommand("iron key"));

        assertTrue(result.isSuccess());
        assertEquals("You take the iron key. It belongs to Brann.", result.getMessage());
        GameEvent stole = game.published.get(0);
        assertEquals(EventType.ITEM, stole.getType());
        assertEquals(EventVerb.TOOK, stole.getVerb());
        assertEquals("stole the iron key from Brann", stole.getDescription());
        assertEquals(-2, stole.getAlignmentDelta());
        assertEquals("blacksmith", game.ari.getInventory().find("iron key").getOwnerId(), "it is still Brann's");
        assertEquals(-2, alignment(game));
    }

    @Test
    void theBlacksmithHandingTheKeyOverIsNotATheft() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");
        game.run(new TalkCommand("blacksmith"));
        Item key = game.state.currentLocation().findItem("iron key");
        game.published.clear();

        ActionResult result = game.run(new TakeCommand("iron key"));

        assertTrue(result.isSuccess());
        assertEquals("Brann hands you the iron key.", result.getMessage());
        assertSame(key, game.ari.getInventory().find("iron key"));
        assertNull(key.getOwnerId(), "now the player's");
        GameEvent received = game.published.get(0);
        assertEquals("received the iron key from Brann", received.getDescription());
        assertEquals(EventVerb.TOOK, received.getVerb(), "a fetch objective still counts it");
        assertEquals(0, received.getAlignmentDelta());
        assertEquals(0, alignment(game));
    }

    @Test
    void anItemThatWasHandedOverIsNoTheftWhenTakenAgainLater() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");
        game.run(new TalkCommand("Brann"));
        game.run(new TakeCommand("iron key"));
        game.run(new LeaveCommand());
        game.run(new DropCommand("iron key"));

        ActionResult result = game.run(new TakeCommand("iron key"));

        assertEquals("You take the iron key.", result.getMessage());
        assertEquals(0, alignment(game));
    }

    @Test
    void aHandedOverItemMustStillFitTheWeightLimit() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "forge");   // carrying 3.5 kg
        game.run(new TalkCommand("Brann"));

        game.assertRejectedWithoutChange(new TakeCommand("warhammer"),
                "The warhammer is too heavy (8 kg). You carry 3.5 of 10 kg.");
    }

    @Test
    void anNpcCannotHandOverAnotherNpcsItem() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.state.currentLocation().addItem(new KeyItem("iron key", 0.2, "blacksmith"));
        game.run(new TalkCommand("Marta"));

        game.assertRejectedWithoutChange(new TakeCommand("iron key"), "The iron key is not Marta's to give.");
    }

    @Test
    void anOwnerWhoIsNotHereIsNamedByTheirId() {
        CommandTestGame game = new CommandTestGame(CharacterClass.WARRIOR, "tavern");
        game.state.currentLocation().addItem(new KeyItem("iron key", 0.2, "blacksmith"));

        assertEquals("You take the iron key. It belongs to blacksmith.", game.run(new TakeCommand("iron key")).getMessage());
        assertNotNull(game.ari.getInventory().find("iron key"));
    }
}
