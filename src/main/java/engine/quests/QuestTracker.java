package engine.quests;

import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.events.GameEventListener;

import java.util.Objects;

/**
 * Advances quest objectives when matching events arrive and finishes quests (SD05).
 *
 * <p>Pattern role: <b>ConcreteObserver</b> of the Observer pattern. For every event it walks the
 * active quests: matching objectives advance; if an objective is blocked the quest fails,
 * otherwise a quest whose objectives are all done is completed and its reward goes to the player.
 * Either outcome is published as a QUEST event: +2 alignment for a completed side quest, -1 for a
 * failed one, 0 for the main quest.
 */
public class QuestTracker implements GameEventListener {

    private static final int SIDE_QUEST_COMPLETED_DELTA = 2;
    private static final int SIDE_QUEST_FAILED_DELTA = -1;

    private final GameState state;
    private final GameEventBus eventBus;

    public QuestTracker(GameState state, GameEventBus eventBus) {
        this.state = Objects.requireNonNull(state, "state");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    @Override
    public void onEvent(GameEvent event) {
        Objects.requireNonNull(event, "event");
        for (Quest quest : state.getQuestLog().activeQuests()) {
            if (quest.getStatus() != QuestStatus.ACTIVE) {
                continue;   // finished by an event published while this one was being handled
            }
            boolean blocked = false;
            for (Objective objective : quest.getObjectives()) {
                if (objective.matches(event)) {
                    objective.advance();
                }
                if (objective.isBlockedBy(event)) {
                    blocked = true;
                }
            }
            if (blocked) {
                quest.fail();
                publish(quest, EventVerb.FAILED, "Quest failed: " + quest.getTitle(), SIDE_QUEST_FAILED_DELTA);
            } else if (quest.isComplete()) {
                quest.complete();
                state.getPlayer().addReward(quest.getReward());
                publish(quest, EventVerb.COMPLETED, "Quest completed: " + quest.getTitle(), SIDE_QUEST_COMPLETED_DELTA);
            }
        }
    }

    private void publish(Quest quest, EventVerb verb, String description, int sideQuestDelta) {
        int delta = quest.isMain() ? 0 : sideQuestDelta;
        eventBus.publish(new GameEvent(EventType.QUEST, state.getTurn(), description, quest.getId(), delta, verb));
    }
}
