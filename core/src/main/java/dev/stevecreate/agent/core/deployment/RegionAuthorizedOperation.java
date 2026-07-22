package dev.stevecreate.agent.core.deployment;

public enum RegionAuthorizedOperation {
    READ_ONLY_SCAN,
    DRY_RUN,
    PLACE_BLOCK,
    REMOVE_BLOCK,
    REPLACE_BLOCK,
    ACCESS_CONTAINER,
    WITHDRAW_ITEM,
    INSERT_ITEM,
    CONNECT_POWER,
    CONNECT_LOGISTICS,
    START_MACHINE,
    CLEANUP,
    ROLLBACK
}
