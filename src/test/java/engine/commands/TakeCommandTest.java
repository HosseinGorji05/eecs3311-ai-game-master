package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.items.Inventory;
import engine.items.Item;
import engine.items.Weapon;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TakeCommandTest {

    private static GameState startGame(CharacterClass cls, String locationId) {
        World world = World.createDefault();
        GameState state = new GameState();
        state.start(CharacterFactory.forClass(cls).createCharacter("Ari", new Stats(4, 3, 3)), world);
        state.setCurrentLocation(world.findLocation(locationId));
        return state;
    }

    private static ActionResult run(GameCommand command, GameState state) {
        List<ActionResult> results = new RulesEngine().executeAll(List.of(command), state);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private static void assertRejectedWithoutChange(GameState state, GameCommand command, String expectedMessage) {
        String before = StateFingerprint.of(state);

        ActionResult result = run(command, state);

        assertFalse(result.isSuccess());
        assertEquals(expectedMessage, result.getMessage());
        assertEquals(before, StateFingerprint.of(state));
    }

    @Test
    void typeIsTake() {
        assertEquals(ActionType.TAKE, new TakeCommand("iron key").getType());
    }

    @Test
    void takeMovesTheItemFromTheLocationIntoTheInventory() {
        GameState state = startGame(CharacterClass.WARRIOR, "tavern");
        Item key = state.currentLocation().findItem("shed key");
        Inventory inventory = state.getPlayer().getInventory();
        double before = inventory.totalWeight();

        ActionResult result = run(new TakeCommand("Shed Key"), state);

        assertTrue(result.isSuccess());
        assertEquals("You take the shed key.", result.getMessage());
        assertNull(state.currentLocation().findItem("shed key"));
        assertSame(key, inventory.find("shed key"));
        assertEquals(before + 0.1, inventory.totalWeight(), 1e-9);
    }

    @Test
    void itemThatWouldExceedTheWeightLimitIsRejectedShowingCurrentAndMaximumWeight() {
        GameState state = startGame(CharacterClass.WARRIOR, "forge");   // sword 3.0 + potion 0.5

        assertRejectedWithoutChange(state, new TakeCommand("warhammer"),
                "The warhammer is too heavy (8 kg). You carry 3.5 of 10 kg.");
        assertNotNull(state.currentLocation().findItem("warhammer"), "the warhammer stays where it is");
    }

    @Test
    void weightCheckCountsItemsInsideNestedContainers() {
        GameState state = startGame(CharacterClass.ROGUE, "forge");   // dagger 1.0 + pouch 0.2 holding potion 0.5
        state.currentLocation().addItem(new Weapon("greataxe", 8.4, 9));
        state.currentLocation().addItem(new Weapon("longsword", 8.3, 7));

        assertRejectedWithoutChange(state, new TakeCommand("greataxe"),
                "The greataxe is too heavy (8.4 kg). You carry 1.7 of 10 kg.");

        assertTrue(run(new TakeCommand("longsword"), state).isSuccess());
        assertEquals(10.0, state.getPlayer().getInventory().totalWeight(), 1e-9);
    }

    @Test
    void missingItemIsRejected() {
        GameState state = startGame(CharacterClass.WARRIOR, "tavern");

        assertRejectedWithoutChange(state, new TakeCommand("dragon egg"), "There is no dragon egg here.");
    }

    @Test
    void itemInAnotherLocationIsOutOfReach() {
        GameState state = startGame(CharacterClass.WARRIOR, "village-square");

        assertRejectedWithoutChange(state, new TakeCommand("shed key"), "There is no shed key here.");
    }

    @Test
    void theSameItemCannotBeTakenTwice() {
        GameState state = startGame(CharacterClass.WARRIOR, "tavern");
        assertTrue(run(new TakeCommand("bread"), state).isSuccess());

        assertRejectedWithoutChange(state, new TakeCommand("bread"), "There is no bread here.");
    }

    @Test
    void validateDoesNotChangeTheState() {
        GameState state = startGame(CharacterClass.WARRIOR, "tavern");
        String before = StateFingerprint.of(state);

        assertTrue(new TakeCommand("shed key").validate(state).isValid());

        assertEquals(before, StateFingerprint.of(state));
    }

    @Test
    void executeWithoutTheItemIsRefused() {
        GameState state = startGame(CharacterClass.WARRIOR, "village-square");
        String before = StateFingerprint.of(state);

        assertThrows(IllegalStateException.class, () -> new TakeCommand("shed key").execute(state, new RulesEngine()));
        assertEquals(before, StateFingerprint.of(state));
    }

    @Test
    void blankOrNullItemNameIsRejected() {
        assertThrows(NullPointerException.class, () -> new TakeCommand(null));
        assertThrows(IllegalArgumentException.class, () -> new TakeCommand(" "));
    }
}
