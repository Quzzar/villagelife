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

    /**
     * Whether this job beds down at night. Night behavior is a fact of the job,
     * not a global rule (docs/worker-loops.md): the guard stands watch through
     * the night instead of sleeping. A sleepless job still keeps its bed and
     * passes the JobClaiming housing gate like any other; only the lying-down
     * is skipped, and the bedtime stow-and-restock runs at their post instead
     * (NightWatchRestockGoal).
     */
    public boolean sleepsAtNight() {
        return this != GUARD;
    }
}
