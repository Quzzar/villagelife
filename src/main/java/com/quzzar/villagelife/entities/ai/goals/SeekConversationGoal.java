package com.quzzar.villagelife.entities.ai.goals;

import java.util.EnumSet;
import java.util.List;

import com.quzzar.villagelife.chat.VillagerConversation;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.relationships.OpinionService;

import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Now and then, a villager with a free moment walks over to someone nearby
 * and strikes up a conversation ({@link VillagerConversation}). Registered at
 * the stroll priority, so work always outranks a chat: only a villager with
 * nothing more pressing goes visiting, though anyone may be VISITED mid-task
 * (the shared chat session pauses them through PauseForConversationGoal, the
 * same way a player opening their screen does).
 *
 * <p>This goal only handles the approach: pick the neighbour, walk over, and
 * hand off to the conversation driver once close. The moment the conversation
 * starts, both parties hold a session, PauseForConversationGoal (priority 3)
 * takes the movement flag from this goal, and this goal bows out. Fondness
 * steers the choice of partner: of everyone in range, the villager walks to
 * the person they think best of, ties broken at random, so friendships built
 * by drift and reflection shape who is seen talking with whom.
 */
public class SeekConversationGoal extends Goal {

  /** How far around the villager looks for someone to talk to. */
  private static final double SEARCH_RANGE = 10.0D;

  /** Close enough to start talking (squared blocks). */
  private static final double START_RANGE_SQR = 2.5D * 2.5D;

  /** The partner outran or outlasted us; give the approach up. */
  private static final double LOSE_RANGE_SQR = 16.0D * 16.0D;
  private static final int GIVE_UP_TICKS = 200;

  /**
   * Mean ticks between attempts once someone is eligible (roughly half a
   * Minecraft minute). The real pacing lever is the conversation cooldown and
   * the server-wide capacity in VillagerConversation; this only keeps the
   * whole village from converging on the first free moment at once.
   */
  private static final int INTERVAL_TICKS = 600;

  private final RealPerson person;
  private final double speedModifier;

  private RealPerson partner;
  private int ticksTrying;
  private boolean done;

  public SeekConversationGoal(RealPerson person, double speedModifier) {
    this.person = person;
    this.speedModifier = speedModifier;
    this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    if (!VillagelifeConfig.LlmVillagerConversations || person.level().isNight() || person.isSleeping()) {
      return false;
    }
    if (person.getRandom().nextInt(reducedTickDelay(INTERVAL_TICKS)) != 0) {
      return false;
    }
    if (!LlmService.get().isReady() || !VillagerConversation.hasCapacity()
        || !VillagerConversation.readyToTalk(person)) {
      return false;
    }
    this.partner = pickPartner();
    return this.partner != null;
  }

  @Override
  public boolean canContinueToUse() {
    return !done && partner != null && partner.isAlive() && !person.isSleeping()
        && ticksTrying < GIVE_UP_TICKS
        && VillagerConversation.readyToTalk(partner)
        && person.distanceToSqr(partner) < LOSE_RANGE_SQR;
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void start() {
    this.ticksTrying = 0;
    this.done = false;
  }

  @Override
  public void stop() {
    this.partner = null;
    this.done = false;
    person.getNavigation().stop();
  }

  @Override
  public void tick() {
    if (partner == null || done) {
      return;
    }
    ticksTrying++;
    person.getLookControl().setLookAt(partner, 30.0F, 30.0F);
    if (person.distanceToSqr(partner) <= START_RANGE_SQR) {
      person.getNavigation().stop();
      // Started or not (the slot may have been taken while walking over),
      // this attempt is spent; the random gate paces the next one.
      VillagerConversation.tryStart(person, partner);
      done = true;
      return;
    }
    if (person.getNavigation().isDone()) {
      person.getNavigation().moveTo(partner, speedModifier);
    }
  }

  /** The best-liked free neighbour in range, ties broken at random; null when alone. */
  private RealPerson pickPartner() {
    List<RealPerson> candidates = person.level().getEntitiesOfClass(RealPerson.class,
        person.getBoundingBox().inflate(SEARCH_RANGE),
        other -> other != person && !other.isSleeping() && VillagerConversation.readyToTalk(other));
    RealPerson best = null;
    int bestScore = Integer.MIN_VALUE;
    for (RealPerson candidate : candidates) {
      // Opinion dominates; the jitter only splits exact ties (a full opinion
      // point outweighs the largest jitter).
      int score = OpinionService.opinionOf(person, candidate.getUUID()) * 10
          + person.getRandom().nextInt(10);
      if (score > bestScore) {
        bestScore = score;
        best = candidate;
      }
    }
    return best;
  }

}
