package com.quzzar.villagelife.village.buildings;

public enum BuildProgress {
    NOT_STARTED(0),
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
            default: return COMPLETE;
        }
    }

}
