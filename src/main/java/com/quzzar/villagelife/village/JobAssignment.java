package com.quzzar.villagelife.village;

import java.io.Serializable;
import java.util.UUID;

public class JobAssignment implements Serializable {
    
    private UUID buildingUUID;
    private int stationIndex;
    private Occupation occupation;
    private UUID personUUID;

    public JobAssignment(UUID personUUID, Occupation occupation, UUID buildingUUID, int stationIndex){
        this.personUUID = personUUID;
        this.occupation = occupation;
        this.buildingUUID = buildingUUID;
        this.stationIndex = stationIndex;
    }

    public UUID getBuildingUUID(){
        return buildingUUID;
    }

    public int getStationIndex(){
        return stationIndex;
    }

    public Occupation getOccupation(){
        return occupation;
    }

    public UUID getPersonUUID(){
        return personUUID;
    }

    public JobAssignment setPersonUUID(UUID personUUID){
        this.personUUID = personUUID;
        return this;
    }

}
