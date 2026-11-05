package presentation;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Stats;
import engine.core.CombatMode;
import engine.core.DialogueMode;
import engine.core.GameState;
import engine.world.World;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static presentation.ControlRules.PanelControl.ANSWER;
import static presentation.ControlRules.PanelControl.ATTACK;
import static presentation.ControlRules.PanelControl.FLEE;
import static presentation.ControlRules.PanelControl.LEAVE;
import static presentation.ControlRules.PanelControl.SAY;
import static presentation.ControlRules.PanelControl.TALK;

/** Which controls of the dialogue panel, the combat panel and the people here work in each mode. */
class ControlRulesPanelTest {

    private final GameState state = new GameState();
    private final World world = World.createDefault();

    ControlRulesPanelTest() {
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)), world);
    }

    private Set<ControlRules.PanelControl> enabled(boolean busy, boolean endingShown) {
        ControlRules rules = ControlRules.of(state);
        Set<ControlRules.PanelControl> on = EnumSet.noneOf(ControlRules.PanelControl.class);
        for (ControlRules.PanelControl control : ControlRules.PanelControl.values()) {
            if (rules.enabled(control, busy, endingShown)) {
                on.add(control);
            }
        }
        return on;
    }

    @Test
    void exploringOnlyTalkingToSomeoneHereWorks() {
        assertEquals(EnumSet.of(TALK), enabled(false, false));
    }

    @Test
    void inAConversationTheDialoguePanelWorks() {
        state.setMode(new DialogueMode(world.findNpc("blacksmith")));

        assertEquals(EnumSet.of(SAY, LEAVE, ANSWER), enabled(false, false));
    }

    @Test
    void inCombatTheCombatPanelWorks() {
        state.setMode(new CombatMode(world.findLocation("wolf-den").findEnemy("wolf")));

        assertEquals(EnumSet.of(ATTACK, FLEE), enabled(false, false));
    }

    @Test
    void nothingWorksWhileBusyAfterTheEndingOrBeforeAGame() {
        state.setMode(new CombatMode(world.findLocation("wolf-den").findEnemy("wolf")));
        assertEquals(EnumSet.noneOf(ControlRules.PanelControl.class), enabled(true, false));
        assertEquals(EnumSet.noneOf(ControlRules.PanelControl.class), enabled(false, true));

        state.getPlayer().takeDamage(100);
        assertEquals(EnumSet.noneOf(ControlRules.PanelControl.class), enabled(false, false));

        ControlRules none = ControlRules.of(new GameState());
        for (ControlRules.PanelControl control : ControlRules.PanelControl.values()) {
            assertEquals(false, none.enabled(control, false, false), control.name());
        }
    }
}
