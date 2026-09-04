package com.quzzar.kithkyn.entities;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.llm.LlmDecision;
import com.quzzar.kithkyn.llm.LlmService;
import com.quzzar.kithkyn.persona.PersonaData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Puts a craft to a villager's own brain: press items from the village stores
 * into a product for their pack, or leave the stores alone. The caller (a
 * job's trigger, like the miner's bedtime torch top-up) decides WHEN the press
 * is legal and how big it is; this class only carries the ask and the hands,
 * per the decide() pattern of docs/llm-brain.md. When the model is absent or
 * silent the craft simply happens: the rules' own choice stands in, so no job
 * ever stalls over a mute model. Only an explicit "leave it" keeps the stores
 * as they are, and both outcomes are logged with the model's reason.
 */
public final class CraftOffer {

  private CraftOffer() {
  }

  /**
   * A conversion the rules already vetted and sized: spend units of the items
   * in {@code spend} (drawn in list order until the ask is covered), each unit
   * pressing into {@code yieldPerUnit} of {@code product}. Prose names the
   * spend after the list's first item, so lead with the one the ask is really
   * about.
   */
  public record Press(List<Item> spend, Item product, int yieldPerUnit) {

    /** The spend as the model reads it ("coal", "bone"). */
    public String spendName() {
      return plain(spend.get(0));
    }

    public String productName() {
      return plain(product);
    }

    private static String plain(Item item) {
      return item.getDescription().getString().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * A situation opener shared by every press ask: who this villager is, in
   * their own persona when they have one. The caller appends the job-specific
   * facts and the question, kept to a few lines on purpose; a long prompt
   * buries a small model's instructions.
   */
  public static String identityLead(RealPerson person) {
    StringBuilder lead = new StringBuilder("You are ").append(person.getFullName()).append(", the ")
        .append(person.getOccupation().name().toLowerCase(Locale.ROOT)).append(" of ")
        .append(person.getVillage().getName()).append(". ");
    PersonaData persona = person.getData(KithkynAttachments.PERSONA.get());
    if (persona != null && !persona.isEmpty() && !persona.blurb().isBlank()) {
      lead.append(persona.blurb()).append(" ");
    }
    return lead.toString();
  }

  /**
   * Offers the press and lands the answer back on the main thread. The gather
   * runs at answer time against live stores, so the press caps at what is
   * really still there however long the model took.
   */
  public static void offer(RealPerson person, BlockPos depositToLoc, Press press, int unitsToSpend,
      String situation) {
    LlmService llm = LlmService.get();
    MinecraftServer server = person.getServer();
    if (!llm.isReady() || server == null) {
      finish(person, depositToLoc, press, unitsToSpend, Optional.empty());
      return;
    }
    List<String> options = List.of(
        "Press " + unitsToSpend + " " + press.spendName() + " into " + unitsToSpend * press.yieldPerUnit()
            + " " + press.productName() + " for your pack",
        "Leave the " + press.spendName() + " in the stores");
    String purpose = person.getFullName() + "'s " + press.productName() + " press";
    llm.decide(purpose, situation, options).whenComplete((decision, error) -> {
      if (error != null) {
        Kithkyn.LOGGER.error("'{}' could not weigh the {} press", person.getFullName(),
            press.productName(), error);
      }
      Optional<LlmDecision> settled = decision == null ? Optional.empty() : decision;
      server.execute(() -> finish(person, depositToLoc, press, unitsToSpend, settled));
    });
  }

  private static void finish(RealPerson person, BlockPos depositToLoc, Press press, int unitsToSpend,
      Optional<LlmDecision> decision) {
    if (!person.isAlive() || person.getVillage() == null) {
      return;
    }
    if (decision.isPresent() && decision.get().choiceIndex() == 1) {
      Kithkyn.LOGGER.info("'{}' left the {} in the stores: {}", person.getFullName(), press.spendName(),
          decision.get().reason());
      return;
    }
    int spent = 0;
    for (Item item : press.spend()) {
      if (spent >= unitsToSpend) {
        break;
      }
      spent += person.getVillage()
          .gatherItemStackFromVillage(new ItemStack(item, unitsToSpend - spent), depositToLoc).getCount();
    }
    if (spent <= 0) {
      return; // the stores emptied while the model thought; nothing to press
    }
    int made = spent * press.yieldPerUnit();
    int stackCap = new ItemStack(press.product()).getMaxStackSize();
    List<ItemStack> stacks = new ArrayList<>();
    for (int remaining = made; remaining > 0; remaining -= stackCap) {
      stacks.add(new ItemStack(press.product(), Math.min(stackCap, remaining)));
    }
    person.addItems(stacks);
    Kithkyn.LOGGER.info("'{}' pressed {} {} into {} {}: {}", person.getFullName(),
        spent, press.spendName(), made, press.productName(),
        decision.map(LlmDecision::reason).filter(reason -> !reason.isBlank())
            .orElse("no answer came, so the rules chose"));
  }

  /**
   * Puts a plain yes/no craft to a villager's own brain and runs {@code craft} on
   * the main thread when the answer is yes - or when the model is silent or absent,
   * so the rules' own choice to make it stands and no craft stalls on a mute model.
   * Only an explicit "leave it" (the second option) holds off. For a craft whose
   * product does not simply land in the pack the way {@link #offer}'s fixed press
   * does: a guard forging a shield it then takes into the off hand. The caller both
   * pays the cost and places the product inside {@code craft}, so it also checks the
   * stores could still pay at answer time, however long the model took.
   */
  public static void offerCraft(RealPerson person, String situation, String make, String leave,
      Runnable craft) {
    LlmService llm = LlmService.get();
    MinecraftServer server = person.getServer();
    if (!llm.isReady() || server == null) {
      craft.run();
      return;
    }
    String purpose = person.getFullName() + "'s craft";
    llm.decide(purpose, situation, List.of(make, leave)).whenComplete((decision, error) -> {
      if (error != null) {
        Kithkyn.LOGGER.error("'{}' could not weigh a craft", person.getFullName(), error);
      }
      Optional<LlmDecision> settled = decision == null ? Optional.empty() : decision;
      server.execute(() -> {
        if (!person.isAlive() || person.getVillage() == null) {
          return;
        }
        if (settled.isPresent() && settled.get().choiceIndex() == 1) {
          Kithkyn.LOGGER.info("'{}' left a craft: {}", person.getFullName(), settled.get().reason());
          return;
        }
        craft.run();
      });
    });
  }
}
