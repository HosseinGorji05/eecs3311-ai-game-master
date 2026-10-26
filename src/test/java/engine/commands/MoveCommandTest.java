package engine.commands;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.GameState;
import engine.core.RulesEngine;
import engine.items.Container;
import engine.items.KeyItem;
import engine.world.Direction;
import engine.world.Exit;
import engine.world.Location;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoveCommandTest {

    private GameState state;
    private World world;
    private Location square;

    @BeforeEach
    void startGame() {
        world = World.createDefault();
        square = world.getStartLocation();
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3)), world);
    }

    private ActionResult run(GameCommand command) {
        List<ActionResult> results = new RulesEngine().executeAll(List.of(command), state);
        assertEquals(1, results.size());
        return results.get(0);
    }

    private void assertRejectedWithoutChange(GameCommand command, String expectedMessagePart) {
        String before = StateFingerprint.of(state);

        ActionResult result = run(command);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains(expectedMessagePart), result.getMessage());
        assertEquals(before, StateFingerprint.of(state));
    }

    @Test
    void typeIsMove() {
        assertEquals(ActionType.MOVE, new MoveCommand(Direction.NORTH).getType());
        assertEquals(ActionType.MOVE, new MoveCommand("Tavern").getType());
    }

    @Test
    void moveInADirectionChangesTheLocationAndMarksItVisited() {
        Location forestEdge = world.findLocation("forest-edge");
        assertFalse(forestEdge.isVisited());

        ActionResult result = run(new MoveCommand(Direction.SOUTH));

        assertTrue(result.isSuccess());
        assertSame(forestEdge, state.currentLocation());
        assertTrue(forestEdge.isVisited());
        assertEquals(forestEdge.describe(), result.getMessage());
        assertTrue(result.getMessage().contains("Exits: north (Village Square)"), result.getMessage());
    }

    @Test
    void moveToAnAdjacentLocationByNameIgnoresCase() {
        ActionResult result = run(new MoveCommand("  tAVERN "));

        assertTrue(result.isSuccess());
        assertSame(world.findLocation("tavern"), state.currentLocation());
        assertTrue(result.getMessage().contains("shed key (0.1 kg)"), result.getMessage());
    }

    @Test
    void movingBackAndForthKeepsWorking() {
        assertTrue(run(new MoveCommand(Direction.WEST)).isSuccess());
        assertTrue(run(new MoveCommand(Direction.EAST)).isSuccess());
        assertSame(square, state.currentLocation());
    }

    @Test
    void noExitThatWayIsRejectedWithTheListOfExits() {
        state.setCurrentLocation(world.findLocation("tavern"));

        assertRejectedWithoutChange(new MoveCommand(Direction.NORTH),
                "There is no exit north. Exits: south (Village Square).");
    }

    @Test
    void destinationThatIsNotAdjacentIsRejected() {
        assertRejectedWithoutChange(new MoveCommand("Bandit Camp"), "travel step by step");
    }

    @Test
    void unknownDestinationIsRejectedWithTheListOfExits() {
        assertRejectedWithoutChange(new MoveCommand("Dragon Lair"),
                "Exits: north (Tavern), west (Blacksmith's Forge), east (Tool Shed, locked), south (Forest Edge).");
    }

    @Test
    void lockedExitWithoutTheKeyIsRejectedNamingTheKey() {
        Exit door = square.getExit(Direction.EAST);
        assertTrue(door.isLocked());

        assertRejectedWithoutChange(new MoveCommand(Direction.EAST), "You need the shed key.");
        assertRejectedWithoutChange(new MoveCommand("Tool Shed"), "You need the shed key.");

        assertTrue(door.isLocked());
        assertSame(square, state.currentLocation());
    }

    @Test
    void lockedExitWithTheKeyIsUnlockedAndTheMoveGoesAhead() {
        Exit door = square.getExit(Direction.EAST);
        state.getPlayer().getInventory().add(new KeyItem("shed key", 0.1));

        ActionResult result = run(new MoveCommand("Tool Shed"));

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().startsWith("You unlock the way with the shed key."), result.getMessage());
        assertSame(world.findLocation("tool-shed"), state.currentLocation());
        assertTrue(state.currentLocation().isVisited());
        assertFalse(door.isLocked());
        assertNotNull(state.getPlayer().getInventory().find("shed key"), "the key is kept");
    }

    @Test
    void unlockedDoorStaysOpenWithoutTheKey() {
        KeyItem key = new KeyItem("shed key", 0.1);
        state.getPlayer().getInventory().add(key);
        assertTrue(run(new MoveCommand(Direction.EAST)).isSuccess());
        assertTrue(run(new MoveCommand(Direction.WEST)).isSuccess());
        state.getPlayer().getInventory().remove("shed key");

        ActionResult result = run(new MoveCommand(Direction.EAST));

        assertTrue(result.isSuccess());
        assertFalse(result.getMessage().contains("unlock"), result.getMessage());
    }

    @Test
    void keyInsideANestedContainerOpensTheDoor() {
        Container pouch = new Container("pouch", 0.2, 4);
        pouch.add(new KeyItem("shed key", 0.1));
        state.getPlayer().getInventory().add(pouch);

        assertTrue(run(new MoveCommand(Direction.EAST)).isSuccess());
        assertSame(world.findLocation("tool-shed"), state.currentLocation());
    }

    @Test
    void wrongKeyDoesNotOpenTheDoor() {
        state.getPlayer().getInventory().add(new KeyItem("iron key", 0.2));

        assertRejectedWithoutChange(new MoveCommand(Direction.EAST), "You need the shed key.");
    }

    @Test
    void validateDoesNotChangeTheState() {
        state.getPlayer().getInventory().add(new KeyItem("shed key", 0.1));
        String before = StateFingerprint.of(state);

        assertTrue(new MoveCommand(Direction.EAST).validate(state).isValid());

        assertEquals(before, StateFingerprint.of(state));
    }

    @Test
    void executeWithoutASuccessfulValidateIsRefused() {
        MoveCommand neverValidated = new MoveCommand(Direction.SOUTH);
        assertThrows(IllegalStateException.class, () -> neverValidated.execute(state, new RulesEngine()));

        MoveCommand invalid = new MoveCommand(Direction.EAST);
        assertFalse(invalid.validate(state).isValid());
        assertThrows(IllegalStateException.class, () -> invalid.execute(state, new RulesEngine()));
        assertSame(square, state.currentLocation());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new MoveCommand((Direction) null));
        assertThrows(NullPointerException.class, () -> new MoveCommand((String) null));
        assertThrows(IllegalArgumentException.class, () -> new MoveCommand("  "));
    }
}
