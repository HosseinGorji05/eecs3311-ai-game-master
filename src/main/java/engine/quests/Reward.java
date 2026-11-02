package engine.quests;

/**
 * What the player receives for completing a quest: gold, experience and optionally an item.
 *
 * <p>The item is only named here: it is an item the quest giver owns, and {@code QuestTracker} hands
 * it over when the quest completes, while {@code Player.addReward()} grants the gold and XP.
 */
public class Reward {

    private final int gold;
    private final int xp;
    private final String itemName;

    /**
     * @param itemName the name of the reward item, or {@code null} if there is none
     * @throws IllegalArgumentException if gold or xp is negative, or the item name is blank
     */
    public Reward(int gold, int xp, String itemName) {
        if (gold < 0 || xp < 0) {
            throw new IllegalArgumentException("Reward gold and XP must not be negative: " + gold + ", " + xp);
        }
        if (itemName != null && itemName.isBlank()) {
            throw new IllegalArgumentException("Reward item name must not be blank");
        }
        this.gold = gold;
        this.xp = xp;
        this.itemName = itemName;
    }

    public int getGold() {
        return gold;
    }

    public int getXp() {
        return xp;
    }

    /** The name of the reward item, or {@code null} if there is none. */
    public String getItemName() {
        return itemName;
    }

    /** "15 gold, 15 XP", followed by ", the iron key" when there is an item. */
    public String describe() {
        return gold + " gold, " + xp + " XP" + (itemName == null ? "" : ", the " + itemName);
    }
}
