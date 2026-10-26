package engine.core;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Stats;
import engine.commands.ActionType;
import engine.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatModeTest {

    private final Enemy wolf = new Enemy("wolf", 8, 3, 11);
    private final CombatMode mode = new CombatMode(wolf);

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"ATTACK", "FLEE", "USE", "EQUIP", "LOOK", "INVENTORY"})
    void allowsTheCombatActions(ActionType type) {
        assertTrue(mode.allows(type));
    }

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"ATTACK", "FLEE", "USE", "EQUIP", "LOOK", "INVENTORY"},
            mode = EnumSource.Mode.EXCLUDE)
    void forbidsEverythingElse(ActionType type) {
        assertFalse(mode.allows(type));
    }

    @Test
    void knowsItsEnemyAndForbidsSaving() {
        assertSame(wolf, mode.getEnemy());
        assertFalse(mode.canSave());
    }

    @Test
    void gameStateDelegatesToTheCombatMode() {
        GameState state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(4, 3, 3)),
                World.createDefault());

        state.setMode(mode);

        assertTrue(state.allows(ActionType.FLEE));
        assertFalse(state.allows(ActionType.MOVE));
        assertFalse(state.canSave());
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> new CombatMode(null));
        assertThrows(NullPointerException.class, () -> mode.allows(null));
    }
}
