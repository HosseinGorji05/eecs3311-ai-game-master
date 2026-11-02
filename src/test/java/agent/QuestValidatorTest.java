package agent;

import engine.character.CharacterClass;
import engine.character.CharacterFactory;
import engine.character.Enemy;
import engine.character.Stats;
import engine.commands.ValidationResult;
import engine.core.DialogueMode;
import engine.core.ExplorationMode;
import engine.core.GameState;
import engine.items.Consumable;
import engine.items.Container;
import engine.items.KeyItem;
import engine.items.Weapon;
import engine.quests.Objective;
import engine.quests.ObjectiveType;
import engine.quests.Quest;
import engine.quests.Reward;
import engine.world.Direction;
import engine.world.Location;
import engine.world.NPC;
import engine.world.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static engine.quests.ObjectiveType.DEFEAT;
import static engine.quests.ObjectiveType.FETCH;
import static engine.quests.ObjectiveType.VISIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every rule of {@link QuestValidator}, each with a rejecting and an accepting case: Ari the warrior
 * talks to Brann the blacksmith in the Forge of the default world.
 */
class QuestValidatorTest {

    private GameState state;
    private QuestValidator validator;
    private NPC brann;

    @BeforeEach
    void talkToBrann() {
        state = new GameState();
        state.start(CharacterFactory.forClass(CharacterClass.WARRIOR).createCharacter("Ari", new Stats(5, 3, 2)),
                World.createDefault());
        state.setCurrentLocation(location("forge"));
        brann = state.currentLocation().findNpc("Brann");
        state.setMode(new DialogueMode(brann));
        validator = new QuestValidator(state);
    }

    private Location location(String id) {
        return state.getWorld().findLocation(id);
    }

    private static Objective objective(ObjectiveType type, String target, int count) {
        return new Objective(type, target, count);
    }

    private static QuestProposal proposal(Objective... objectives) {
        return proposal("Herbs for the Forge", new Reward(15, 15, null), objectives);
    }

    private static QuestProposal proposal(String title, Reward reward, Objective... objectives) {
        return new QuestProposal(title, "Bring me what I need.", List.of(objectives), reward, null);
    }

    private static QuestProposal herbs() {
        return proposal(objective(FETCH, "healing herbs", 1));
    }

    private void assertValid(QuestProposal proposal) {
        ValidationResult result = validator.validate(proposal, brann);
        assertTrue(result.isValid(), result.getReason());
        assertNull(result.getReason());
    }

    private void assertBroken(QuestValidator.Rule rule, QuestProposal proposal) {
        assertBroken(rule, validator.validate(proposal, brann));
    }

    private static void assertBroken(QuestValidator.Rule rule, ValidationResult result) {
        assertTrue(!result.isValid() && result.getReason().startsWith(rule.name() + ": "),
                "expected " + rule + " but got " + result.getReason());
    }

    private static Quest quest(String id, String title, String giverId, Objective objective) {
        return new Quest(id, title, "Old business.", giverId, false, List.of(objective), new Reward(5, 5, null));
    }

    @Test
    void aProposalBuiltFromTheRealWorldIsValid() {
        assertValid(herbs());
        assertTrue(validator.validate(herbs()).isValid(), "the giver defaults to the NPC the player talks to");
    }

    // GIVER

    @Test
    void giverMustBeTheQuestGivingNpcThePlayerIsTalkingTo() {
        NPC marta = location("tavern").findNpc("Marta");
        assertBroken(QuestValidator.Rule.GIVER, validator.validate(herbs(), marta));

        state.setCurrentLocation(location("tavern"));
        state.setMode(new DialogueMode(marta));
        assertBroken(QuestValidator.Rule.GIVER, validator.validate(herbs(), marta));
        assertTrue(validator.validate(herbs(), marta).getReason().contains("Marta does not give quests"));

        state.setMode(new ExplorationMode());
        assertBroken(QuestValidator.Rule.GIVER, validator.validate(herbs()));
    }

    @Test
    void giverNamedInTheProposalMustBeTheRealGiver() {
        QuestProposal fromMarta = new QuestProposal("Herbs", "Bring herbs.",
                List.of(objective(FETCH, "healing herbs", 1)), new Reward(1, 1, null), "Marta");
        QuestProposal fromTheBlacksmith = new QuestProposal("Herbs", "Bring herbs.",
                List.of(objective(FETCH, "healing herbs", 1)), new Reward(1, 1, null), "the blacksmith");

        assertBroken(QuestValidator.Rule.GIVER, fromMarta);
        assertValid(fromTheBlacksmith);
    }

    // OPEN_QUEST and QUEST_LOG_FULL

    @Test
    void noOfferWhileThePlayerHasAnOpenQuestFromTheGiver() {
        Quest open = quest("blacksmith:wolf", "The Wolf", "blacksmith", objective(DEFEAT, "wolf", 1));
        state.getQuestLog().accept(open);
        assertBroken(QuestValidator.Rule.OPEN_QUEST, herbs());

        open.complete();
        assertValid(herbs());
    }

    @Test
    void aFourthSideQuestIsRejected() {
        state.getQuestLog().accept(quest("a", "A", "someone", objective(VISIT, "wolf-den", 1)));
        state.getQuestLog().accept(quest("b", "B", "someone", objective(VISIT, "old-mill", 1)));
        assertValid(herbs());

        Quest third = quest("c", "C", "someone", objective(VISIT, "tavern", 1));
        state.getQuestLog().accept(third);
        assertBroken(QuestValidator.Rule.QUEST_LOG_FULL, herbs());

        third.fail();
        assertValid(herbs());
    }

    // TITLE and DESCRIPTION

    @Test
    void titleMustBeNonBlankAndShort() {
        Objective fetch = objective(FETCH, "healing herbs", 1);
        assertBroken(QuestValidator.Rule.TITLE, proposal("  ", new Reward(1, 1, null), fetch));
        assertBroken(QuestValidator.Rule.TITLE, proposal("x".repeat(61), new Reward(1, 1, null), fetch));
        assertValid(proposal("x".repeat(QuestValidator.MAX_TITLE_CHARS), new Reward(1, 1, null), fetch));
    }

    @Test
    void descriptionMustBeNonBlankAndShort() {
        List<Objective> fetch = List.of(objective(FETCH, "healing herbs", 1));
        Reward reward = new Reward(1, 1, null);
        assertBroken(QuestValidator.Rule.DESCRIPTION, new QuestProposal("Herbs", "", fetch, reward, null));
        assertBroken(QuestValidator.Rule.DESCRIPTION, new QuestProposal("Herbs", "y".repeat(301), fetch, reward, null));
        assertValid(new QuestProposal("Herbs", "y".repeat(QuestValidator.MAX_DESCRIPTION_CHARS), fetch, reward, null));
    }

    // OBJECTIVE_COUNT

    @Test
    void aQuestNeedsOneToThreeObjectives() {
        assertBroken(QuestValidator.Rule.OBJECTIVE_COUNT, proposal());
        Objective herbs = objective(FETCH, "healing herbs", 1);
        Objective wolf = objective(DEFEAT, "wolf", 1);
        Objective camp = objective(VISIT, "bandit-camp", 1);
        Objective mill = objective(VISIT, "old-mill", 1);
        assertBroken(QuestValidator.Rule.OBJECTIVE_COUNT, proposal(herbs, wolf, camp, mill));
        assertValid(proposal(herbs, wolf, camp));
    }

    // TARGET_EXISTS

    @Test
    void targetsThatDoNotExistAreRejected() {
        assertBroken(QuestValidator.Rule.TARGET_EXISTS, proposal(objective(FETCH, "Crystal of Zorn", 1)));
        assertBroken(QuestValidator.Rule.TARGET_EXISTS, proposal(objective(DEFEAT, "dragon", 1)));
        assertBroken(QuestValidator.Rule.TARGET_EXISTS, proposal(objective(VISIT, "castle", 1)));
        ValidationResult carried = validator.validate(proposal(objective(FETCH, "sword", 1)), brann);
        assertBroken(QuestValidator.Rule.TARGET_EXISTS, carried);
        assertTrue(carried.getReason().contains("already carries it"), carried.getReason());

        assertValid(proposal(objective(FETCH, "Healing Herbs", 1)));
        assertValid(proposal(objective(DEFEAT, "wolf", 1)));
        assertValid(proposal(objective(VISIT, "Old Mill", 1)));
    }

    @Test
    void aDefeatedEnemyNoLongerExists() {
        Location den = location("wolf-den");
        den.removeEnemy(den.findEnemy("wolf"));

        assertBroken(QuestValidator.Rule.TARGET_EXISTS, proposal(objective(DEFEAT, "wolf", 1)));
        assertValid(proposal(objective(DEFEAT, "bandit leader", 1)));
    }

    // TARGET_AVAILABLE

    @Test
    void anNpcsItemAndThePlayersOwnLocationAreNotAvailable() {
        ValidationResult owned = validator.validate(proposal(objective(FETCH, "iron key", 1)), brann);
        assertBroken(QuestValidator.Rule.TARGET_AVAILABLE, owned);
        assertTrue(owned.getReason().contains("belongs to Brann"), owned.getReason());
        assertBroken(QuestValidator.Rule.TARGET_AVAILABLE, proposal(objective(VISIT, "forge", 1)));

        assertValid(proposal(objective(FETCH, "bread", 1)));
        assertValid(proposal(objective(VISIT, "village-square", 1)));
    }

    // TARGET_REACHABLE

    @Test
    void theLockedToolShedIsReachableWhileItsKeyLiesInTheTavern() {
        assertValid(proposal(objective(FETCH, "hand axe", 1)));
        assertValid(proposal(objective(VISIT, "tool-shed", 1)));
    }

    @Test
    void aLockedExitWithoutItsKeyMakesTheTargetUnreachable() {
        location("tavern").removeItem("shed key");
        location("tool-shed").addEnemy(new Enemy("rat", 2, 1, 5));

        assertBroken(QuestValidator.Rule.TARGET_REACHABLE, proposal(objective(FETCH, "hand axe", 1)));
        assertBroken(QuestValidator.Rule.TARGET_REACHABLE, proposal(objective(VISIT, "tool-shed", 1)));
        assertBroken(QuestValidator.Rule.TARGET_REACHABLE, proposal(objective(DEFEAT, "rat", 1)));
    }

    @Test
    void aCarriedKeyAKeyInAContainerOrAnUnlockedDoorOpenTheWay() {
        Location tavern = location("tavern");
        KeyItem key = (KeyItem) tavern.removeItem("shed key");
        QuestProposal axe = proposal(objective(FETCH, "hand axe", 1));

        state.getPlayer().getInventory().add(key);
        assertValid(axe);

        state.getPlayer().getInventory().remove("shed key");
        Container chest = new Container("chest", 1.0, 2);
        chest.add(key);
        location("village-square").addItem(chest);
        assertValid(axe);

        location("village-square").removeItem("chest");
        assertBroken(QuestValidator.Rule.TARGET_REACHABLE, axe);
        location("village-square").getExit(Direction.EAST).unlock(key);
        assertValid(axe);
    }

    // TARGET_COUNT

    @Test
    void requiredCountsMustNotExceedWhatExists() {
        assertBroken(QuestValidator.Rule.TARGET_COUNT, proposal(objective(FETCH, "healing herbs", 2)));
        assertBroken(QuestValidator.Rule.TARGET_COUNT,
                proposal(objective(FETCH, "healing herbs", 1), objective(FETCH, "healing herbs", 1)));
        assertBroken(QuestValidator.Rule.TARGET_COUNT, proposal(objective(DEFEAT, "wolf", 2)));
        assertBroken(QuestValidator.Rule.TARGET_COUNT, proposal(objective(VISIT, "old-mill", 2)));

        location("old-mill").addItem(new Consumable("healing herbs", 0.2, 5));
        assertValid(proposal(objective(FETCH, "healing herbs", 2)));
    }

    // REWARD_ITEM

    @Test
    void theRewardItemMustBeTheGiversAndLightEnoughToCarry() {
        Objective herbs = objective(FETCH, "healing herbs", 1);
        assertBroken(QuestValidator.Rule.REWARD_ITEM, proposal("Herbs", new Reward(5, 5, "bread"), herbs));
        assertBroken(QuestValidator.Rule.REWARD_ITEM, proposal("Herbs", new Reward(5, 5, "dragon egg"), herbs));
        location("forge").addItem(new Weapon("anvil", 50.0, 1, "blacksmith"));
        assertBroken(QuestValidator.Rule.REWARD_ITEM, proposal("Herbs", new Reward(5, 5, "anvil"), herbs));

        assertValid(proposal("Herbs", new Reward(5, 5, "warhammer"), herbs));
        assertValid(proposal("Herbs", new Reward(5, 5, "Iron Key"), herbs));
    }

    // DUPLICATE

    @Test
    void aDuplicateOfAnActiveOrCompletedQuestIsRejected() {
        Quest active = quest("x", "Herbs for the Forge", "someone", objective(VISIT, "wolf-den", 1));
        state.getQuestLog().accept(active);
        assertBroken(QuestValidator.Rule.DUPLICATE, proposal("HERBS FOR THE FORGE", new Reward(1, 1, null),
                objective(VISIT, "old-mill", 1)));
        assertBroken(QuestValidator.Rule.DUPLICATE, proposal("Wolf Den Walk", new Reward(1, 1, null),
                objective(VISIT, "Wolf Den", 1)));

        active.complete();
        assertBroken(QuestValidator.Rule.DUPLICATE, herbs());
    }

    @Test
    void aFailedQuestMayBeOfferedAgain() {
        Quest failed = quest("x", "Herbs for the Forge", "blacksmith", objective(FETCH, "healing herbs", 1));
        state.getQuestLog().accept(failed);
        failed.fail();

        assertValid(herbs());
    }

    @Test
    void canonicalTargetsUseTheGamesNames() {
        assertEquals("old-mill", validator.canonicalTarget(VISIT, " old mill "));
        assertEquals("tool-shed", validator.canonicalTarget(VISIT, "TOOL-SHED"));
        assertEquals("healing herbs", validator.canonicalTarget(FETCH, "Healing Herbs"));
        assertEquals("bandit leader", validator.canonicalTarget(DEFEAT, "Bandit Leader"));
        assertNull(validator.canonicalTarget(FETCH, "Crystal of Zorn"));
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThrows(NullPointerException.class, () -> new QuestValidator(null));
        assertThrows(NullPointerException.class, () -> validator.validate(null, brann));
        assertThrows(NullPointerException.class, () -> validator.validate(herbs(), null));
    }
}
