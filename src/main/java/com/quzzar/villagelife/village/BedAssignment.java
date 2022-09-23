package com.quzzar.villagelife.village;

import java.io.Serializable;
import java.util.UUID;

public class BedAssignment implements Serializable {
    
    private UUID buildingUUID;
    private int bedIndex;
    private UUID personUUID;

    public BedAssignment(UUID personUUID, UUID buildingUUID, int bedIndex){
        this.personUUID = personUUID;
        this.buildingUUID = buildingUUID;
        this.bedIndex = bedIndex;
    }

    public UUID getBuildingUUID(){
        return buildingUUID;
    }

    public int getBedIndex(){
        return bedIndex;
    }

    public UUID getPersonUUID(){
        return personUUID;
    }

    public BedAssignment setPersonUUID(UUID personUUID){
        this.personUUID = personUUID;
        return this;
    }

}
