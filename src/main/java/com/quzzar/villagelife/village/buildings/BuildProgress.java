package com.quzzar.villagelife.village.buildings;

public enum BuildProgress {
    /**
     * The builder is carrying the recipe home before a single block is owed:
     * items gathered into their pack, and only when the whole recipe is in hand
     * are they consumed and the build committed. Nothing is placed and nothing
     * is paid until then, so a builder killed mid-gather leaves the village no
     * poorer and the project uncommitted.
     */
    GATHERING(5),
    NOT_STARTED(0),
    /** Clearing and levelling the ground before any structure block is placed. */
    PREPARING(4),
    IN_PROGRESS_WORKING(1),
    IN_PROGRESS_PAUSED(2),
    COMPLETE(3);

    private int progress;
    private BuildProgress(int progress) {
        this.progress = progress;
    }

    public int toInt(){
        return progress;
    }

    public static BuildProgress fromInt(int progress){
        switch(progress){
            case 0: return NOT_STARTED;
            case 1: return IN_PROGRESS_WORKING;
            case 2: return IN_PROGRESS_PAUSED;
            case 3: return COMPLETE;
            case 4: return PREPARING;
            case 5: return GATHERING;
            default: return COMPLETE;
        }
    }

}
