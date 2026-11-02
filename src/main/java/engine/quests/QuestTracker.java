package engine.quests;

import engine.character.Player;
import engine.core.GameState;
import engine.events.EventType;
import engine.events.EventVerb;
import engine.events.GameEvent;
import engine.events.GameEventBus;
import engine.events.GameEventListener;
import engine.items.Item;
import engine.world.Location;
import engine.world.NPC;
import engine.world.Region;

import java.util.Objects;

/**
 * Advances quest objectives when matching events arrive and finishes quests (SD05).
 *
 * <p>Pattern role: <b>ConcreteObserver</b> of the Observer pattern. For every event it walks the
 * active quests: matching objectives advance; if an objective is blocked the quest fails,
 * otherwise a quest whose objectives are all done is completed and its reward goes to the player.
 * Either outcome is published as a QUEST event: +2 alignment for a completed side quest, -1 for a
 * failed one, 0 for the main quest.
 *
 * <p><b>Reward item (F08).</b> {@link Reward#getItemName()} names an item the quest giver owns. On
 * completion the giver hands it over: it leaves its location, goes into the player's inventory and
 * stops belonging to the giver (an ITEM event without a verb records it, so it never counts as a
 * FETCH). If it is too heavy to carry, it stays where it is but becomes the player's, so taking it
 * later is not a theft. If the player already carries it (stolen earlier), the giver lets them keep
 * it; if it is gone, only the gold and XP are granted. The QUEST event's description says which.
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
                String handover = handOverRewardItem(quest);
                publish(quest, EventVerb.COMPLETED, "Quest completed: " + quest.getTitle() + ". Reward: "
                        + quest.getReward().describe() + "." + handover, SIDE_QUEST_COMPLETED_DELTA);
            }
        }
    }

    private void publish(Quest quest, EventVerb verb, String description, int sideQuestDelta) {
        int delta = quest.isMain() ? 0 : sideQuestDelta;
        eventBus.publish(new GameEvent(EventType.QUEST, state.getTurn(), description, quest.getId(), delta, verb));
    }

    /** Hands the reward item over as described in the class comment; the sentence to add, or "". */
    private String handOverRewardItem(Quest quest) {
        String itemName = quest.getReward().getItemName();
        if (itemName == null) {
            return "";
        }
        NPC giver = quest.getGiverId() == null ? null : state.getWorld().findNpc(quest.getGiverId());
        String giverName = giver == null ? "The giver" : giver.getName();
        Player player = state.getPlayer();
        for (Region region : state.getWorld().getRegions()) {
            for (Location location : region.getLocations()) {
                for (Item item : location.getItems()) {
                    if (item.getName().equalsIgnoreCase(itemName) && isOwnedByGiver(item, quest)) {
                        item.clearOwner();
                        if (!player.getInventory().canCarry(item)) {
                            publishItem(giverName + " left the " + item.getName() + " for " + player.getName()
                                    + " at the " + location.getName(), item);
                            return " The " + item.getName() + " is too heavy for you to carry, so " + giverName
                                    + " leaves it for you at the " + location.getName() + ".";
                        }
                        location.removeItem(item.getName());
                        player.getInventory().add(item);
                        publishItem("received the " + item.getName() + " from " + giverName + " as a quest reward", item);
                        return " " + giverName + " gives you the " + item.getName() + ".";
                    }
                }
            }
        }
        Item carried = player.getInventory().find(itemName);
        if (carried != null && isOwnedByGiver(carried, quest)) {
            carried.clearOwner();
            return " " + giverName + " lets you keep the " + carried.getName() + " you took.";
        }
        return " " + giverName + " no longer has the " + itemName + ".";
    }

    private static boolean isOwnedByGiver(Item item, Quest quest) {
        return item.getOwnerId() != null && item.getOwnerId().equals(quest.getGiverId());
    }

    private void publishItem(String description, Item item) {
        eventBus.publish(new GameEvent(EventType.ITEM, state.getTurn(), description, item.getName(), 0));
    }
}
