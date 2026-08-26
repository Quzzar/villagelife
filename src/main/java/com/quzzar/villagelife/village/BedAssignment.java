package com.quzzar.villagelife.village;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

public class BedAssignment {

    public static final Codec<BedAssignment> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.optionalFieldOf("person").forGetter(a -> Optional.ofNullable(a.getPersonUUID())),
            UUIDUtil.CODEC.fieldOf("building").forGetter(BedAssignment::getBuildingUUID),
            Codec.INT.fieldOf("bed").forGetter(BedAssignment::getBedIndex)
    ).apply(inst, (person, building, bed) -> new BedAssignment(person.orElse(null), building, bed)));

    private UUID buildingUUID;
    private int bedIndex;
    private UUID personUUID;

    public BedAssignment(UUID personUUID, UUID buildingUUID, int bedIndex) {
        this.personUUID = personUUID;
        this.buildingUUID = buildingUUID;
        this.bedIndex = bedIndex;
    }

    public UUID getBuildingUUID() {
        return buildingUUID;
    }

    public int getBedIndex() {
        return bedIndex;
    }

    public UUID getPersonUUID() {
        return personUUID;
    }

    public BedAssignment setPersonUUID(UUID personUUID) {
        this.personUUID = personUUID;
        return this;
    }

}
