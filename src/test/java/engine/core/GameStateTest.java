package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Player;
import engine.character.Stats;
import engine.commands.ActionType;
import engine.world.Location;
import engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateTest {

    private static Player newPlayer() {
        return CharacterFactory.forClass(CharacterClass.ROGUE).createCharacter("Ari", new Stats(3, 4, 3));
    }

    /** A mode that allows nothing and forbids saving, to show that GameState delegates to its mode. */
    private static final GameMode NOTHING_ALLOWED = new GameMode() {
        @Override
        public boolean allows(ActionType type) {
            return false;
        }

        @Override
        public boolean canSave() {
            return false;
        }
    };

    @Test
    void newStateHasNotStarted() {
        GameState state = new GameState();

        assertEquals(0, state.getTurn());
        assertNull(state.getPlayer());
        assertNull(state.getWorld());
        assertNull(state.currentLocation());
        assertNull(state.getMode());
        assertThrows(IllegalStateException.class, () -> state.allows(ActionType.MOVE));
        assertThrows(IllegalStateException.class, state::canSave);
    }

    @Test
    void startPlacesThePlayerInTheVisitedStartLocationInExplorationModeOnTurnOne() {
        GameState state = new GameState();
        Player player = newPlayer();
        World world = World.createDefault();

        state.start(player, world);

        assertSame(player, state.getPlayer());
        assertSame(world, state.getWorld());
        assertSame(world.getStartLocation(), state.currentLocation());
        assertTrue(state.currentLocation().isVisited());
        assertInstanceOf(ExplorationMode.class, state.getMode());
        assertEquals(1, state.getTurn());
    }

    @Test
    void startRejectsNull() {
        GameState state = new GameState();
        assertThrows(NullPointerException.class, () -> state.start(null, World.createDefault()));
        assertThrows(NullPointerException.class, () -> state.start(newPlayer(), null));
    }

    @Test
    void setCurrentLocationChangesTheLocationButRejectsNull() {
        GameState state = new GameState();
        World world = World.createDefault();
        state.start(newPlayer(), world);
        Location tavern = world.findLocation("tavern");

        state.setCurrentLocation(tavern);
        assertSame(tavern, state.currentLocation());

        assertThrows(NullPointerException.class, () -> state.setCurrentLocation(null));
        assertSame(tavern, state.currentLocation());
    }

    @Test
    void nextTurnAdvancesTheTurnByOne() {
        GameState state = new GameState();
        state.start(newPlayer(), World.createDefault());

        state.nextTurn();
        state.nextTurn();

        assertEquals(3, state.getTurn());
    }

    @ParameterizedTest
    @EnumSource(ActionType.class)
    void allowsAndCanSaveDelegateToTheCurrentMode(ActionType type) {
        GameState state = new GameState();
        state.start(newPlayer(), World.createDefault());
        assertEquals(new ExplorationMode().allows(type), state.allows(type));
        assertTrue(state.canSave());

        state.setMode(NOTHING_ALLOWED);

        assertSame(NOTHING_ALLOWED, state.getMode());
        assertFalse(state.allows(type));
        assertFalse(state.canSave());
    }

    @Test
    void setModeRejectsNull() {
        GameState state = new GameState();
        state.start(newPlayer(), World.createDefault());
        assertThrows(NullPointerException.class, () -> state.setMode(null));
        assertInstanceOf(ExplorationMode.class, state.getMode());
    }
}
