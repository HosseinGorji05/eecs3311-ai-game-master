package engine.commands;

/** The kinds of action a player can take; there is one {@link GameCommand} per type. */
public enum ActionType {
    MOVE,
    TAKE,
    DROP,
    PUT,
    USE,
    EQUIP,
    TALK,
    LEAVE,
    ATTACK,
    FLEE,
    LOOK,
    INVENTORY
}
