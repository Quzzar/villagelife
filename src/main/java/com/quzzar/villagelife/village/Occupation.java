package com.quzzar.villagelife.village;

public enum Occupation {
    IDLE,
    GUARD,
    BLACKSMITH,
    FARMER,
    CLERIC,
    /** Legacy alias for {@link #IDLE}; kept only so persisted "NITWIT" strings still decode. */
    @Deprecated
    NITWIT,
    LIBRARIAN,
    MERCHANT,
    LUMBERJACK,
    BUILDER,
    MINER,
    LEADER;

    public boolean isIdle() {
        return this == IDLE || this == NITWIT;
    }
}
