package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.commands.ActionType;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static presentation.ControlRules.Control.DROP;
import static presentation.ControlRules.Control.EQUIP;
import static presentation.ControlRules.Control.LOAD;
import static presentation.ControlRules.Control.MOVE;
import static presentation.ControlRules.Control.NEW_GAME;
import static presentation.ControlRules.Control.SAVE;
import static presentation.ControlRules.Control.SEND;
import static presentation.ControlRules.Control.TAKE;
import static presentation.ControlRules.Control.USE;

/** Which GUI controls work in each game mode, without a display. */
class ControlRulesTest {

    private final GameState state = new GameState();
    private final World world = World.createDefault();

    ControlRulesTest() {
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)), world);
    }

    private Set<ControlRules.Control> enabled(boolean busy, boolean endingShown) {
        ControlRules rules = ControlRules.of(state);
        Set<ControlRules.Control> on = EnumSet.noneOf(ControlRules.Control.class);
        for (ControlRules.Control control : ControlRules.Control.values()) {
            if (rules.enabled(control, busy, endingShown)) {
                on.add(control);
            }
        }
        return on;
    }

    @Test
    void whileExploringEverythingButNewGameWorks() {
        assertEquals(EnumSet.of(SEND, MOVE, TAKE, USE, EQUIP, DROP, SAVE, LOAD), enabled(false, false));
    }

    @Test
    void inCombatOnlyTheCombatActionsTypingAndSaveWhichTheControllerRefusesWork() {
        state.setMode(new CombatMode(world.findLocation("wolf-den").findEnemy("wolf")));

        assertEquals(EnumSet.of(SEND, USE, EQUIP, SAVE, LOAD), enabled(false, false));
    }

    @Test
    void inAConversationTakeIsOffAlthoughTheModeAllowsTheNpcsHandover() {
        state.setMode(new DialogueMode(world.findNpc("tavern-keeper")));

        assertTrue(state.allows(ActionType.TAKE));
        assertEquals(EnumSet.of(SEND, SAVE, LOAD), enabled(false, false));
        assertTrue(ControlRules.of(state).isTalking());
    }

    @Test
    void whileATurnIsPendingNothingWorks() {
        assertEquals(EnumSet.noneOf(ControlRules.Control.class), enabled(true, false));
    }

    @Test
    void afterTheEndingOnlyLoadAndNewGameWork() {
        state.getPlayer().takeDamage(state.getPlayer().getMaxHp());

        assertTrue(state.isGameOver());
        assertEquals(EnumSet.of(LOAD, NEW_GAME), enabled(false, false));
    }

    @Test
    void theEndScreenAloneAlsoLeavesOnlyLoadAndNewGame() {
        assertEquals(EnumSet.of(LOAD, NEW_GAME), enabled(false, true));
    }

    @Test
    void beforeAGameStartsOnlyLoadAndNewGameWork() {
        ControlRules rules = ControlRules.of(new GameState());

        assertFalse(rules.isStarted());
        for (ControlRules.Control control : ControlRules.Control.values()) {
            assertEquals(control == LOAD || control == NEW_GAME, rules.enabled(control, false, false), control.name());
        }
    }
}
