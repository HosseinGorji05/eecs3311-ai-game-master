package engine.core;

import engine.commands.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorationModeTest {

    private final ExplorationMode mode = new ExplorationMode();

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"FLEE", "LEAVE"}, mode = EnumSource.Mode.EXCLUDE)
    void allowsEveryActionExceptFleeAndLeave(ActionType type) {
        assertTrue(mode.allows(type));
    }

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"FLEE", "LEAVE"})
    void forbidsFleeAndLeave(ActionType type) {
        assertFalse(mode.allows(type));
    }

    @Test
    void savingIsAllowed() {
        assertTrue(mode.canSave());
    }

    @Test
    void nullTypeIsRejected() {
        assertThrows(NullPointerException.class, () -> mode.allows(null));
    }
}
