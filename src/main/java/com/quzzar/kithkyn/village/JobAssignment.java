package com.quzzar.kithkyn.village;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.quzzar.kithkyn.utils.KithkynCodecs;

import net.minecraft.core.UUIDUtil;

public class JobAssignment {

    /**
     * Stable workplace identity for posts derived from this village's one wall.
     * The station index points into {@code WallPosts.plan}, just as a building
     * assignment points into its declared workstation list.
     */
    public static final UUID WALL_WORKPLACE_ID = UUID.fromString(
            "77616c6c-706f-7374-0000-000000000001");

    public static final Codec<JobAssignment> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.optionalFieldOf("person").forGetter(a -> Optional.ofNullable(a.getPersonUUID())),
            KithkynCodecs.forEnum(Occupation.class).fieldOf("occupation").forGetter(JobAssignment::getOccupation),
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

    /** An unclaimed guard assignment for one derived wall post. */
    public static JobAssignment wallPost(int stationIndex) {
        return new JobAssignment(null, Occupation.GUARD, WALL_WORKPLACE_ID, stationIndex);
    }

    public boolean isWallPost() {
        return WALL_WORKPLACE_ID.equals(this.buildingUUID);
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
