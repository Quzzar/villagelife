package com.quzzar.villagelife.village;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.villagelife.utils.VillagelifeCodecs;

import net.minecraft.core.UUIDUtil;

public class JobAssignment {

    public static final Codec<JobAssignment> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.optionalFieldOf("person").forGetter(a -> Optional.ofNullable(a.getPersonUUID())),
            VillagelifeCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(JobAssignment::getOccupation),
            UUIDUtil.CODEC.fieldOf("building").forGetter(JobAssignment::getBuildingUUID),
            Codec.INT.fieldOf("station").forGetter(JobAssignment::getStationIndex)
    ).apply(inst, (person, occupation, building, station) ->
            new JobAssignment(person.orElse(null), occupation, building, station)));

    private UUID buildingUUID;
    private int stationIndex;
    private Occupation occupation;
    private UUID personUUID;

    public JobAssignment(UUID personUUID, Occupation occupation, UUID buildingUUID, int stationIndex) {
        this.personUUID = personUUID;
        this.occupation = occupation;
        this.buildingUUID = buildingUUID;
        this.stationIndex = stationIndex;
    }

    public UUID getBuildingUUID() {
        return buildingUUID;
    }

    public int getStationIndex() {
        return stationIndex;
    }

    public Occupation getOccupation() {
        return occupation;
    }

    public UUID getPersonUUID() {
        return personUUID;
    }

    public JobAssignment setPersonUUID(UUID personUUID) {
        this.personUUID = personUUID;
        return this;
    }

}
