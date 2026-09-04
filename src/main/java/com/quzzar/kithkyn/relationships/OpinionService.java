package com.quzzar.kithkyn.relationships;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.entities.PersonSocialData;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.KithkynAttachments;
import com.quzzar.kithkyn.village.Village;

import net.minecraft.util.Mth;

/**
 * How a villager's feeling about someone changes: the one tool the villager's
 * brain calls, whoever the someone is.
 *
 * The division of labour is the point. Game code records what happened and
 * never decides what it meant: a villager lost a job, was saved from a
 * creeper, was handed a diamond. Whether that makes them grateful, resentful,
 * or indifferent is the villager's own judgement, made by their brain either
 * in conversation or on reflection, and expressed as a call to this class.
 *
 * A target is just a UUID, so the same call serves a fellow villager and a
 * player. Only the storage differs, because the two mean different things:
 * feelings about a resident live in the village's relationship web as that
 * person's private lean on the pair, while feelings about a player (or anyone
 * who is not a resident) live on the villager themselves.
 */
public final class OpinionService {

  /** The most one judgement may move a feeling, in either direction. */
  public static final int MAX_STEP = 15;

  private OpinionService() {
  }

  /**
   * Applies one judgement. Returns what was actually applied after clamping,
   * which may be less than asked for.
   */
  public static int apply(RealPerson person, UUID target, int requested, String reason) {
    if (target == null || requested == 0 || target.equals(person.getUUID())) {
      return 0;
    }
    int delta = Mth.clamp(requested, -MAX_STEP, MAX_STEP);

    Village village = person.getVillage();
    if (village != null && village.hasResident(target)) {
      RelationshipDrift.nudgeOneSided(village, person.getUUID(), target, delta, reason);
    } else {
      applyToOutsider(person, target, delta);
    }

    Kithkyn.LOGGER.debug("[opinion] {} toward {}: {}{} ({})",
        person.getFullName(), target, delta > 0 ? "+" : "", delta, reason);
    return delta;
  }

  /** How this villager currently feels about anyone: resident or outsider. */
  public static int opinionOf(RealPerson person, UUID target) {
    Village village = person.getVillage();
    if (village != null && village.hasResident(target)) {
      RelationshipPair pair = village.getRelationship(person.getUUID(), target);
      return pair == null ? 0 : pair.opinionOf(person.getUUID());
    }
    return person.getData(KithkynAttachments.SOCIAL.get())
        .relationships().getOrDefault(target, 0);
  }

  private static void applyToOutsider(RealPerson person, UUID target, int delta) {
    PersonSocialData social = person.getData(KithkynAttachments.SOCIAL.get());
    Map<UUID, Integer> relationships = new HashMap<>(social.relationships());
    int updated = Mth.clamp(relationships.getOrDefault(target, 0) + delta, -100, 100);
    relationships.put(target, updated);
    person.setData(KithkynAttachments.SOCIAL.get(), social.withRelationships(relationships));
  }

}
