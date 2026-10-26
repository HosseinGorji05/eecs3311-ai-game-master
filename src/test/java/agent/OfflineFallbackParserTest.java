package agent;

import engine.commands.ActionProposal;
import engine.commands.ActionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineFallbackParserTest {

    private final OfflineFallbackParser parser = new OfflineFallbackParser();

    private ActionProposal single(String text) {
        List<ActionProposal> proposals = parser.parse(text);
        assertEquals(1, proposals.size(), text + " -> " + proposals);
        return proposals.get(0);
    }

    @ParameterizedTest(name = "\"{0}\" is a move to {1}")
    @CsvSource({
            "go east, east",
            "Go East!, east",
            "east, east",
            "e, east",
            "n, north",
            "move south, south",
            "walk west, west",
            "go to the tavern, tavern",
            "go to Tool Shed, tool shed",
            "head to the forest edge, forest edge",
            "run north, north"
    })
    void movesInADirectionOrToAPlace(String text, String target) {
        ActionProposal move = single(text);
        assertEquals(ActionType.MOVE, move.getType());
        assertEquals(target, move.getTarget());
    }

    @ParameterizedTest(name = "\"{0}\" takes {1}")
    @CsvSource({
            "take shed key, shed key",
            "take the shed key, shed key",
            "TAKE THE SHED KEY., shed key",
            "pick up the iron key, iron key",
            "pick the iron key up, iron key",
            "get bread, bread",
            "grab a hand axe, hand axe"
    })
    void takesAnItem(String text, String target) {
        ActionProposal take = single(text);
        assertEquals(ActionType.TAKE, take.getType());
        assertEquals(target, take.getTarget());
    }

    @ParameterizedTest(name = "\"{0}\" attacks {1}")
    @CsvSource({
            "attack wolf, wolf",
            "attack the wolf, wolf",
            "fight the bandit leader, bandit leader",
            "kill wolf, wolf",
            "hit the wolf, wolf"
    })
    void attacksAnEnemy(String text, String target) {
        ActionProposal attack = single(text);
        assertEquals(ActionType.ATTACK, attack.getType());
        assertEquals(target, attack.getTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flee", "run", "run away", "escape", "Flee!"})
    void fleesWithoutATarget(String text) {
        ActionProposal flee = single(text);
        assertEquals(ActionType.FLEE, flee.getType());
        assertNull(flee.getTarget());
    }

    @Test
    void recognisesTheOtherActionTypesToo() {
        assertEquals(ActionType.LOOK, single("look").getType());
        assertEquals(ActionType.INVENTORY, single("i").getType());
        assertEquals(ActionType.LEAVE, single("leave").getType());
        assertEquals(ActionType.DROP, single("drop the bread").getType());
        assertEquals(ActionType.USE, single("drink healing potion").getType());
        assertEquals(ActionType.EQUIP, single("equip sword").getType());
        ActionProposal talk = single("talk to the blacksmith");
        assertEquals(ActionType.TALK, talk.getType());
        assertEquals("blacksmith", talk.getTarget());
        ActionProposal put = single("put the potion into the pouch");
        assertEquals(ActionType.PUT, put.getType());
        assertEquals("potion", put.getTarget());
        assertEquals("pouch", put.getSecondTarget());
    }

    @Test
    void severalActionsAreSplitOnAndThenAndCommas() {
        List<ActionProposal> proposals = parser.parse("take the shed key and go south, then go east");

        assertEquals(List.of(ActionType.TAKE, ActionType.MOVE, ActionType.MOVE),
                proposals.stream().map(ActionProposal::getType).toList());
        assertEquals(List.of("shed key", "south", "east"), proposals.stream().map(ActionProposal::getTarget).toList());
    }

    @Test
    void namesContainingAndAreNotSplit() {
        assertEquals("bandit leader", single("attack the bandit leader").getTarget());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "dance wildly", "give me 1000 gold", "take it", "attack them",
            "go north and dance", "take"})
    void anythingNotUnderstoodGivesNoActionsAtAll(String text) {
        assertTrue(parser.parse(text).isEmpty(), text);
    }

    @Test
    void nullGivesNoActions() {
        assertTrue(parser.parse(null).isEmpty());
    }
}
