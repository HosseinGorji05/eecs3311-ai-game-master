package presentation;

import engine.character.CharacterClass;
import engine.character.Stats;
import engine.commands.ActionProposal;
import engine.commands.ActionType;
import engine.core.FixedDiceRoller;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.world.Location;
import llm.ScriptedLLMClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combat panel's content (F04, SD04, SD12), without a display. The rolls come from real fights through
 * the controller with scripted dice, so the text read back is the rules engine's own.
 */
class CombatSummaryTest {

    /** A warrior (strength 5, agility 3, defense 13, unarmed damage 2) in the Wolf Den. */
    private static GameController warriorAtTheWolf(int... rolls) {
        GameController controller = new GameController(new ScriptedLLMClient(List.of()), new FixedDiceRoller(rolls));
        controller.newGame("Ari", CharacterClass.WARRIOR, new Stats(5, 3, 2));
        controller.startGame();
        GameState state = controller.getState();
        Location den = state.getWorld().findLocation("wolf-den");
        state.setCurrentLocation(den);
        den.markVisited();
        return controller;
    }

    private static TurnResult attack(GameController controller) {
        return controller.performAction(new ActionProposal(ActionType.ATTACK, "wolf", null));
    }

    @Test
    void inCombatBothFightersHpAndTheRoundsHitAndMissAreShown() {
        GameController controller = warriorAtTheWolf(20, 1);

        attack(controller);
        CombatSummary summary = CombatSummary.of(controller.getState());

        assertTrue(summary.isFighting());
        assertEquals(new CombatSummary.Fighter("Ari", 30, 30), summary.getPlayer());
        assertEquals(new CombatSummary.Fighter("wolf", 6, 8), summary.getEnemy());
        assertEquals(List.of("Ari rolled 20 + 5 = 25 against defense 11: hit, 2 damage (wolf 6/8 HP).",
                "wolf rolled 1 + 3 = 4 against defense 13: miss."), summary.getLastRound());
    }

    @Test
    void theEnemysHitShowsThePlayersHp() {
        GameController controller = warriorAtTheWolf(1, 20);

        attack(controller);
        CombatSummary summary = CombatSummary.of(controller.getState());

        assertEquals(new CombatSummary.Fighter("Ari", 27, 30), summary.getPlayer());
        assertEquals("wolf rolled 20 + 3 = 23 against defense 13: hit, 3 damage (Ari 27/30 HP).",
                summary.getLastRound().get(1));
    }

    @Test
    void onlyTheLatestRoundIsShown() {
        GameController controller = warriorAtTheWolf(1, 1, 20, 1);

        attack(controller);
        attack(controller);

        assertEquals(List.of("Ari rolled 20 + 5 = 25 against defense 11: hit, 2 damage (wolf 6/8 HP).",
                "wolf rolled 1 + 3 = 4 against defense 13: miss."), CombatSummary.of(controller.getState()).getLastRound());
    }

    @Test
    void aKillEndsTheFightAndTheRoundShowsTheDefeat() {
        GameController controller = warriorAtTheWolf(20, 1, 20, 1, 20, 1, 20);
        for (int round = 0; round < 4; round++) {
            attack(controller);
        }

        CombatSummary summary = CombatSummary.of(controller.getState());

        assertFalse(summary.isFighting());
        assertNull(summary.getEnemy());
        assertEquals(List.of("Ari rolled 20 + 5 = 25 against defense 11: hit, 2 damage (wolf 0/8 HP).",
                "The wolf is defeated."), summary.getLastRound());
    }

    @Test
    void aFleeShowsItsOutcomeOnlyBecauseTheEngineKeepsNoFleeRoll() {
        GameController escaped = warriorAtTheWolf(1, 1, 20);
        attack(escaped);
        escaped.performAction(new ActionProposal(ActionType.FLEE, null, null));
        GameController caught = warriorAtTheWolf(1, 1, 1, 20);
        attack(caught);
        caught.performAction(new ActionProposal(ActionType.FLEE, null, null));

        assertFalse(CombatSummary.of(escaped.getState()).isFighting());
        assertEquals(List.of("Flee: escaped from the wolf."), CombatSummary.of(escaped.getState()).getLastRound());
        assertTrue(CombatSummary.of(caught.getState()).isFighting());
        assertEquals(List.of("Flee: failed to escape from the wolf.",
                "wolf rolled 20 + 3 = 23 against defense 13: hit, 3 damage (Ari 27/30 HP)."),
                CombatSummary.of(caught.getState()).getLastRound());
    }

    @Test
    void aDeathIsShownFromItsEvent() {
        GameEvent died = new GameEvent(EventType.COMBAT, 4, "Ari died", "Ari", 0, EventVerb.DIED);

        assertEquals("Ari died.", CombatSummary.describe(died));
    }

    @Test
    void theEnginesAttackTextIsReadBackExactly() {
        CombatSummary.Attack hit = CombatSummary.parseAttack(
                "bandit leader attacks Ari: rolls 12 + 4 = 16 against defense 16. Hit for 4 damage (Ari: 20/24 HP).");
        CombatSummary.Attack miss = CombatSummary.parseAttack("Ari attacks wolf: rolls 3 + 2 = 5 against defense 11. Miss.");

        assertEquals(new CombatSummary.Attack("bandit leader", "Ari", 12, 4, 16, 16, true, 4, 20, 24), hit);
        assertEquals(new CombatSummary.Attack("Ari", "wolf", 3, 2, 5, 11, false, 0, -1, -1), miss);
        assertNull(CombatSummary.parseAttack("The wolf is defeated."));
    }

    @Test
    void textThatIsNotAKnownCombatLineIsShownAsItIs() {
        GameEvent odd = new GameEvent(EventType.COMBAT, 2, "the wolf howls", "wolf", 0);

        assertEquals("the wolf howls", CombatSummary.describe(odd));
    }

    @Test
    void beforeAnyFightThereIsNothingToShow() {
        GameController controller = warriorAtTheWolf();

        CombatSummary summary = CombatSummary.of(controller.getState());

        assertFalse(summary.isFighting());
        assertTrue(summary.getLastRound().isEmpty());
        assertFalse(CombatSummary.of(new GameState()).isFighting());
    }
}
