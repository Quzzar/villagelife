package com.quzzar.villagelife.economy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.llm.LlmDecision;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.village.Village;
import com.quzzar.villagelife.village.buildings.BuildingInfo;
import com.quzzar.villagelife.village.buildings.Buildings;
import com.quzzar.villagelife.village.buildings.StructureInProgress;
import com.quzzar.villagelife.village.buildings.VillageGoal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A village trading with nobody present (docs/economy.md, "Initiative").
 *
 * The counterparty is the bank: an abstract exchange, always available, that
 * pays a quarter of value and charges four times it. That spread is the whole
 * point — it lets a village drowning in wood and starving for iron convert on
 * its own at a bad rate, which is how biome poverty resolves without a player,
 * while leaving any real player a visibly better deal.
 *
 * Rules propose, the brain approves. The arithmetic here finds only legal,
 * beneficial moves — surplus above what the village has reserved, shortages it
 * genuinely cannot cover — and the model picks among them and says why. Nothing
 * waits on inference: with no model, the rules' own first choice stands.
 */
public final class VillageTrading {

  /**
   * What a market grants when the village may trade unattended. The design puts
   * this on a level-2 market; the placeholder market carries it for now, so the
   * behaviour is exercisable before the real market levels are authored, and
   * moving it is a datapack edit rather than a code change.
   */
  public static final String INITIATIVE = "TRADE_INITIATIVE";

  /** Village seconds between one unattended trade and the next. */
  public static final int TRADE_INTERVAL_SECONDS = 300;

  /** Never sell the last of anything: below this, an item is not surplus. */
  private static final int KEEP_FLOOR = 16;

  /** The most of one item a village will move in a single trade. */
  private static final int MAX_BUNDLE = 32;

  /** Below this there is nothing worth walking to the bank for. */
  private static final int MIN_BUNDLE = 8;

  /**
   * How many things to sell the brain is asked to choose between. A small model
   * choosing from a long list picks worse than one choosing from a short one,
   * and the first list this produced ran to everything the village owned.
   */
  private static final int MAX_SELL_OPTIONS = 3;

  private static final String NO_TRADE = "nothing: keep what we have";

  private VillageTrading() {
  }

  /** One proposal: what would move, which way, and what it is worth. */
  private record Deal(boolean selling, Item item, int count, int emeralds, String description) {}

  /**
   * Considers one unattended trade. Returns false when the village is not
   * eligible or has nothing worth doing, so the caller can leave its clock
   * alone rather than counting a decision that never happened.
   */
  public static boolean consider(Village village, ServerLevel level) {
    if (!village.canDo(INITIATIVE)) {
      return false;
    }
    Optional<String> blocked = Treasury.tradeBlocker(village, level);
    if (blocked.isPresent()) {
      return false;
    }

    List<Deal> deals = propose(village, level);
    if (deals.isEmpty()) {
      return false;
    }

    Deal ruleChoice = deals.get(0);
    if (!LlmService.get().isReady()) {
      execute(village, level, ruleChoice, "it was the best we could do");
      return true;
    }

    List<String> options = new ArrayList<>(deals.stream().map(Deal::description).toList());
    options.add(NO_TRADE);
    Villagelife.LOGGER.debug("Village '{}' is weighing {} trades with the bank",
        village.getName(), deals.size());
    LlmService.get().decide(situationOf(village, level), options)
        .thenAccept(decision -> level.getServer().execute(
            () -> settle(village, level, deals, ruleChoice, decision)));
    return true;
  }

  private static void settle(Village village, ServerLevel level, List<Deal> deals,
      Deal ruleChoice, Optional<LlmDecision> decision) {
    if (decision.isEmpty()) {
      execute(village, level, ruleChoice, "nobody had a better idea");
      return;
    }
    LlmDecision chosen = decision.get();
    int index = chosen.choiceIndex();
    if (index == deals.size()) {
      Villagelife.LOGGER.info("Village '{}' traded nothing with the bank: {}",
          village.getName(), chosen.reason());
      return;
    }
    if (index < 0 || index >= deals.size()) {
      execute(village, level, ruleChoice, "the answer made no sense, so the rules chose");
      return;
    }
    execute(village, level, deals.get(index), chosen.reason());
  }

  /**
   * Every legal, beneficial move, best first. Selling comes from genuine
   * surplus — what is held, less what the village has already committed to a
   * project, less a floor it will not sell past — and buying only ever covers
   * something it cannot otherwise afford.
   */
  private static List<Deal> propose(Village village, ServerLevel level) {
    List<Deal> deals = new ArrayList<>();
    Set<Item> seen = new HashSet<>();

    for (ItemStack stack : village.getVillageInventory()) {
      Item item = stack.getItem();
      if (item == Treasury.CURRENCY || !seen.add(item)) {
        continue;
      }
      int spare = VillagePricing.countHeld(village, item) - reserved(village, item) - KEEP_FLOOR;
      if (spare < MIN_BUNDLE) {
        continue;
      }
      Optional<Double> unit = Bank.buyPrice(item, level.getServer());
      if (unit.isEmpty()) {
        continue;
      }
      int count = Math.min(spare, MAX_BUNDLE);
      int emeralds = (int) Math.floor(unit.get() * count);
      if (emeralds < 1 || emeralds > roomForEmeralds(village, level)) {
        continue;
      }
      deals.add(new Deal(true, item, count, emeralds,
          "sell " + count + " " + plain(item) + " to the bank for " + emeralds + " emeralds"));
    }
    deals.sort((a, b) -> Integer.compare(b.emeralds(), a.emeralds()));

    if (deals.size() > MAX_SELL_OPTIONS) {
      deals = new ArrayList<>(deals.subList(0, MAX_SELL_OPTIONS));
    }

    ItemStack missing = blockingMaterial(village);
    if (missing != null) {
      Optional<Double> unit = Bank.sellPrice(missing.getItem(), level.getServer());
      int balance = Treasury.balance(village, level);
      if (unit.isPresent() && balance > 0) {
        int affordable = (int) Math.floor(balance / Math.max(unit.get(), 0.0001D));
        int count = Math.min(Math.min(missing.getCount(), MAX_BUNDLE), affordable);
        int cost = (int) Math.ceil(unit.get() * count);
        if (count > 0 && cost <= balance) {
          // A village that cannot finish what it started for want of one
          // material is exactly the village the bank's bad rate exists for.
          deals.add(new Deal(false, missing.getItem(), count, cost,
              "buy " + count + " " + plain(missing.getItem()) + " from the bank for " + cost
                  + " emeralds, to finish what we are building"));
        }
      }
    }
    return deals;
  }

  /**
   * The one material standing between the village and the thing it is actually
   * doing: what its current project, or the goal it is saving for, still needs
   * and does not have. Deliberately NOT "anything in the catalogue it cannot
   * afford" — that answers a question nobody asked, and a village asked it
   * bought cobblestone it was standing on.
   */
  private static ItemStack blockingMaterial(Village village) {
    StructureInProgress project = village.getCurrentProject();
    ItemStack missing = project == null || project.getBuilding() == null
        ? null
        : shortfall(village, project.getBuilding().getInfo());
    if (missing != null) {
      return missing;
    }
    String goal = VillageGoal.current(village);
    return goal == null ? null : shortfall(village, Buildings.getByName(goal));
  }

  /** The first cost item this building needs more of than the village holds. */
  private static ItemStack shortfall(Village village, BuildingInfo info) {
    if (info == null) {
      return null;
    }
    for (ItemStack cost : info.getMaterialCost()) {
      int short_ = cost.getCount() - VillagePricing.countHeld(village, cost.getItem());
      if (short_ > 0) {
        return new ItemStack(cost.getItem(), short_);
      }
    }
    return null;
  }

  /** Moves the goods and the money, in an order that cannot half-complete. */
  private static void execute(Village village, ServerLevel level, Deal deal, String reason) {
    if (deal.selling()) {
      int taken = village.gatherItemStackFromVillage(new ItemStack(deal.item(), deal.count())).getCount();
      if (taken <= 0) {
        return;
      }
      Optional<Double> unit = Bank.buyPrice(deal.item(), level.getServer());
      int paid = unit.map(value -> (int) Math.floor(value * taken)).orElse(0);
      int unbanked = Treasury.deposit(village, level, paid);
      Villagelife.LOGGER.info("Village '{}' sold {} {} to the bank for {} emeralds: {}",
          village.getName(), taken, plain(deal.item()), paid - unbanked, reason);
      return;
    }

    int cost = deal.emeralds();
    if (Treasury.balance(village, level) < cost) {
      return;
    }
    // Goods first, so a full village never pays for what it cannot hold.
    ItemStack leftover = village.storeAwayFrom(new ItemStack(deal.item(), deal.count()), List.of());
    int accepted = deal.count() - leftover.getCount();
    if (accepted <= 0) {
      return;
    }
    Optional<Double> unit = Bank.sellPrice(deal.item(), level.getServer());
    int owed = unit.map(value -> (int) Math.ceil(value * accepted)).orElse(cost);
    Treasury.withdraw(village, level, owed);
    Villagelife.LOGGER.info("Village '{}' bought {} {} from the bank for {} emeralds: {}",
        village.getName(), accepted, plain(deal.item()), owed, reason);
  }

  /**
   * What the village has already committed: the project it is building and the
   * one it is saving for. A village that sells the logs its own half-built
   * lumberjack needs has traded itself backwards.
   */
  private static int reserved(Village village, Item item) {
    int reserved = 0;
    StructureInProgress project = village.getCurrentProject();
    if (project != null && project.getBuilding() != null) {
      reserved += costOf(project.getBuilding().getInfo(), item);
    }
    String goal = VillageGoal.current(village);
    if (goal != null) {
      reserved += costOf(Buildings.getByName(goal), item);
    }
    return reserved;
  }

  private static int costOf(BuildingInfo info, Item item) {
    if (info == null) {
      return 0;
    }
    int cost = 0;
    for (ItemStack stack : info.getMaterialCost()) {
      if (stack.getItem() == item) {
        cost += stack.getCount();
      }
    }
    return cost;
  }

  /** How many emeralds the till could actually accept right now. */
  private static int roomForEmeralds(Village village, ServerLevel level) {
    int room = 0;
    for (Container container : Treasury.chests(village, level)) {
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
          room += Math.min(Items.EMERALD.getDefaultMaxStackSize(), container.getMaxStackSize());
        } else if (stack.getItem() == Treasury.CURRENCY) {
          room += Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
      }
    }
    return room;
  }

  private static String situationOf(Village village, ServerLevel level) {
    return "You are the collective judgement of " + village.getName()
        + ", a settlement of " + village.getPopulation().size() + " people with "
        + Treasury.balance(village, level) + " emeralds in the market chest. "
        + "The bank pays badly and charges dearly, but it is always open and asks no favours. "
        + "Choose one trade to make today, or none.";
  }

  private static String plain(Item item) {
    return item.getDescription().getString().toLowerCase();
  }

}
