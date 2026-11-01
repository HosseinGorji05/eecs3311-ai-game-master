package engine.core;

import engine.character.Enemy;
import engine.commands.ActionType;
import engine.world.NPC;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueModeTest {

    private static final NPC MARTA = new NPC("tavern-keeper", "Marta", "tavern keeper", "warm", List.of(), List.of(), false);

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"LEAVE", "TAKE", "LOOK", "INVENTORY"})
    void allowsLeavingTakingWhatIsHandedOverLookingAndTheInventory(ActionType type) {
        assertTrue(new DialogueMode(MARTA).allows(type));
    }

    @ParameterizedTest
    @EnumSource(value = ActionType.class, names = {"LEAVE", "TAKE", "LOOK", "INVENTORY"}, mode = EnumSource.Mode.EXCLUDE)
    void forbidsEverythingElse(ActionType type) {
        assertFalse(new DialogueMode(MARTA).allows(type));
    }

    @Test
    void knowsItsNpcAndAllowsSaving() {
        DialogueMode mode = new DialogueMode(MARTA);

        assertSame(MARTA, mode.getNpc());
        assertTrue(mode.canSave());
        assertThrows(NullPointerException.class, () -> new DialogueMode(null));
        assertThrows(NullPointerException.class, () -> mode.allows(null));
    }

    @Test
    void theOtherModesDoNotAllowTalkingTheSameWay() {
        assertTrue(new ExplorationMode().allows(ActionType.TALK));
        assertFalse(new ExplorationMode().allows(ActionType.LEAVE));
        assertFalse(new CombatMode(new Enemy("wolf", 8, 3, 11)).allows(ActionType.TALK));
    }
}
