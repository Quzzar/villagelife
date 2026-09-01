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
    MASON,
    TANNER,
    HUNTER,
    FISHER,
    // New-category occupations (bakery, butchery, brewery, pasture, inn). Present so their
    // building defs load through forEnum. BAKER, BUTCHER, and HERDER have work loops in
    // RealPerson.registerGoals; BREWER and INNKEEPER still lack theirs (#47) and behave
    // like the other loop-less jobs: assigned, but producing nothing.
    BAKER,
    BUTCHER,
    BREWER,
    HERDER,
    INNKEEPER,
    QUARTERMASTER,
    LEADER;

    public boolean isIdle() {
        return this == IDLE || this == NITWIT;
    }
}
