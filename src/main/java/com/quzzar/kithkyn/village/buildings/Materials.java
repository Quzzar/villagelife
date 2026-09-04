package com.quzzar.kithkyn.village.buildings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

/**
 * What pays for what (docs/building-spec.md, "Cost is a recipe").
 *
 * A recipe names one item per line, but the village pays in kind. Three kinds
 * of material are generic: a log cost is met by any log, a plank cost by any
 * plank, a stone cost by any cobblestone, cobbled deepslate or sandstone,
 * whatever the guard felled or the miner dug, and a wool cost by wool of any
 * colour, whatever the herder sheared. Wood is wood and stone is stone (decided
 * 2026-09-01) and wool is wool (2026-09-02), so a camp in a mangrove swamp, over
 * a desert mine or beside a flock of black sheep builds from what it actually
 * has. Every other material is exact.
 *
 * Logs also pay for planks, {@link #PLANKS_PER_LOG} to a log, with nobody
 * sawing: a recipe that asks for planks is satisfied by the logs its log lines
 * have not claimed. Planks never pay for logs. Because one recipe can ask for
 * both, that rule can only be settled per recipe ({@link #shortfall},
 * {@link #spend}); the per-item helpers count one kind strictly and answer
 * single-material questions such as a wall's stone.
 *
 * This is the one place the rule lives. The planner's tally, a goal's
 * shortfall, the builder's gathering and the commit that spends the recipe,
 * the wall's draw and the market's reserve all ask here rather than comparing
 * items themselves.
 */
public final class Materials {

  /** Planks a single log stands in for when a recipe asks for planks. */
  public static final int PLANKS_PER_LOG = 4;

  /** The generic kinds a cost line can be paid from; EXACT lines want that item alone. */
  public enum Kind { LOGS, PLANKS, STONE, WOOL, EXACT }

  private Materials() {
  }

  public static Kind kindOf(Item item) {
    var holder = item.builtInRegistryHolder();
    if (holder.is(ItemTags.LOGS)) {
      return Kind.LOGS;
    }
    if (holder.is(ItemTags.PLANKS)) {
      return Kind.PLANKS;
    }
    if (holder.is(Tags.Items.COBBLESTONES) || holder.is(Tags.Items.SANDSTONE_BLOCKS)) {
      return Kind.STONE;
    }
    if (holder.is(ItemTags.WOOL)) {
      return Kind.WOOL;
    }
    return Kind.EXACT;
  }

  /** Whether the item is a log of any wood. */
  public static boolean isLog(Item item) {
    return kindOf(item) == Kind.LOGS;
  }

  /** Whether the item is a plank of any wood. */
  public static boolean isPlank(Item item) {
    return kindOf(item) == Kind.PLANKS;
  }

  /** Whether the item is stone as a mine yields it: cobblestone, cobbled deepslate, sandstone. */
  public static boolean isStone(Item item) {
    return kindOf(item) == Kind.STONE;
  }

  /** Whether the item is wool of any colour, as the herder shears it. */
  public static boolean isWool(Item item) {
    return kindOf(item) == Kind.WOOL;
  }

  /** Whether an item in hand pays toward a cost that names another, kind for kind. */
  public static boolean pays(Item held, Item wanted) {
    if (held == wanted) {
      return true;
    }
    Kind kind = kindOf(wanted);
    return kind != Kind.EXACT && kindOf(held) == kind;
  }

  /**
   * Whether an item in hand can go toward a cost line at all, borrowing
   * included: a log is worth fetching for a plank line. What a fetch may pick
   * up, where {@link #pays} is what a line is counted in.
   */
  public static boolean paysToward(Item held, Item wanted) {
    return pays(held, wanted) || (isPlank(wanted) && isLog(held));
  }

  /** How much of a stock tally counts toward this cost item, kind for kind. */
  public static int counted(Map<Item, Integer> stock, Item wanted) {
    int total = 0;
    for (Map.Entry<Item, Integer> entry : stock.entrySet()) {
      if (pays(entry.getKey(), wanted)) {
        total += entry.getValue();
      }
    }
    return total;
  }

  /** {@link #counted} with logs standing in for planks, for "is there any way toward this at all". */
  public static int countedToward(Map<Item, Integer> stock, Item wanted) {
    int total = counted(stock, wanted);
    if (isPlank(wanted)) {
      total += kindTotal(stock, Kind.LOGS) * PLANKS_PER_LOG;
    }
    return total;
  }

  /** How much a container holds that pays toward this cost item, kind for kind. */
  public static int held(Container container, Item wanted) {
    int total = 0;
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (!stack.isEmpty() && pays(stack.getItem(), wanted)) {
        total += stack.getCount();
      }
    }
    return total;
  }

  /** {@link #held} with logs standing in for planks: whether a chest is worth the walk for this line. */
  public static int heldToward(Container container, Item wanted) {
    int total = held(container, wanted);
    if (isPlank(wanted)) {
      total += held(container, Items.OAK_LOG) * PLANKS_PER_LOG;
    }
    return total;
  }

  /** Everything a container holds, as the tally the recipe helpers settle against. */
  public static Map<Item, Integer> tally(Container container) {
    Map<Item, Integer> stock = new HashMap<>();
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (!stack.isEmpty()) {
        stock.merge(stack.getItem(), stack.getCount(), Integer::sum);
      }
    }
    return stock;
  }

  /**
   * The lines of a recipe the stock cannot cover, each with what is still
   * missing. Settled in the one order that never counts a log twice: log lines
   * claim logs first, plank lines take planks and then the logs left over at
   * four to one, and stone, wool and exact lines take their own kind. Empty
   * when the recipe is affordable.
   */
  public static List<ItemStack> shortfall(Map<Item, Integer> stock, List<ItemStack> recipe) {
    int logs = kindTotal(stock, Kind.LOGS);
    int planks = kindTotal(stock, Kind.PLANKS);
    // The kinds paid from a shared pool, any item of the kind: what is left of
    // each as the recipe's lines draw on it.
    Map<Kind, Integer> pooled = new EnumMap<>(Kind.class);
    pooled.put(Kind.STONE, kindTotal(stock, Kind.STONE));
    pooled.put(Kind.WOOL, kindTotal(stock, Kind.WOOL));
    Map<Item, Integer> exact = new HashMap<>(stock);
    List<ItemStack> missing = new ArrayList<>();
    for (ItemStack cost : recipe) {
      if (isLog(cost.getItem())) {
        int use = Math.min(cost.getCount(), logs);
        logs -= use;
        addMissing(missing, cost, cost.getCount() - use);
      }
    }
    for (ItemStack cost : recipe) {
      if (isPlank(cost.getItem())) {
        int need = cost.getCount();
        int use = Math.min(need, planks);
        planks -= use;
        need -= use;
        if (need > 0) {
          int borrowed = Math.min(logs, ceilDiv(need, PLANKS_PER_LOG));
          logs -= borrowed;
          need -= borrowed * PLANKS_PER_LOG;
        }
        addMissing(missing, cost, need);
      }
    }
    for (ItemStack cost : recipe) {
      Kind kind = kindOf(cost.getItem());
      if (kind == Kind.LOGS || kind == Kind.PLANKS) {
        continue;
      }
      int have;
      if (kind == Kind.EXACT) {
        int in = exact.getOrDefault(cost.getItem(), 0);
        have = Math.min(cost.getCount(), in);
        exact.put(cost.getItem(), in - have);
      } else {
        int pool = pooled.get(kind);
        have = Math.min(cost.getCount(), pool);
        pooled.put(kind, pool - have);
      }
      addMissing(missing, cost, cost.getCount() - have);
    }
    return missing;
  }

  /** {@link #shortfall} against what a container holds. */
  public static List<ItemStack> shortfall(Container container, List<ItemStack> recipe) {
    return shortfall(tally(container), recipe);
  }

  /** Whether the stock pays the whole recipe, logs borrowed for planks included. */
  public static boolean covers(Map<Item, Integer> stock, List<ItemStack> recipe) {
    return shortfall(stock, recipe).isEmpty();
  }

  /**
   * Takes up to {@code amount} of whatever pays toward the cost item out of
   * the container, kind for kind, last slots first as {@code Utils.removeItem}
   * does, and returns what came out: one stack per item, so a builder paying a
   * log cost from a mixed store carries a mixed load.
   */
  public static List<ItemStack> take(Container container, Item wanted, int amount) {
    List<ItemStack> taken = new ArrayList<>();
    int left = amount;
    for (int slot = container.getContainerSize() - 1; slot >= 0 && left > 0; slot--) {
      ItemStack stack = container.getItem(slot);
      if (stack.isEmpty() || !pays(stack.getItem(), wanted)) {
        continue;
      }
      ItemStack piece = stack.split(left);
      left -= piece.getCount();
      addTo(taken, piece);
    }
    if (!taken.isEmpty()) {
      container.setChanged();
    }
    return taken;
  }

  /**
   * {@link #take} with borrowing: a plank line takes planks first and then
   * logs, one per {@link #PLANKS_PER_LOG} planks still owed, rounded up. The
   * spare planks in that rounding are the price of nobody sawing.
   */
  public static List<ItemStack> takeToward(Container container, Item wanted, int amount) {
    List<ItemStack> taken = take(container, wanted, amount);
    int owed = amount - total(taken);
    if (owed > 0 && isPlank(wanted)) {
      taken.addAll(take(container, Items.OAK_LOG, ceilDiv(owed, PLANKS_PER_LOG)));
    }
    return taken;
  }

  /**
   * Spends a whole recipe out of a container, settled in the same order as
   * {@link #shortfall}, and returns what was taken. The caller checks
   * {@link #covers} first; this is the one place a recipe is paid.
   */
  public static List<ItemStack> spend(Container container, List<ItemStack> recipe) {
    List<ItemStack> spent = new ArrayList<>();
    for (ItemStack cost : recipe) {
      if (isLog(cost.getItem())) {
        spent.addAll(take(container, cost.getItem(), cost.getCount()));
      }
    }
    for (ItemStack cost : recipe) {
      if (isPlank(cost.getItem())) {
        spent.addAll(takeToward(container, cost.getItem(), cost.getCount()));
      }
    }
    for (ItemStack cost : recipe) {
      Kind kind = kindOf(cost.getItem());
      if (kind != Kind.LOGS && kind != Kind.PLANKS) {
        spent.addAll(take(container, cost.getItem(), cost.getCount()));
      }
    }
    return spent;
  }

  /**
   * The shortfall against a stock as a villager would say it, e.g. "40
   * cobblestone, 3 white wool"; empty when the recipe is covered. The chat
   * briefing and the bedtime chest question both state what the village is
   * still short of, and they must agree with what the brain is waiting on.
   */
  public static String describeShortfall(Map<Item, Integer> stock, List<ItemStack> recipe) {
    List<String> parts = new ArrayList<>();
    for (ItemStack missing : shortfall(stock, recipe)) {
      parts.add(missing.getCount() + " " + describe(missing.getItem()));
    }
    return String.join(", ", parts);
  }

  /**
   * The cost item in words, for the brain's briefing and a villager's mouth.
   * A generic kind is spoken as what pays for it, "logs", "planks" or
   * "cobblestone"; anything else is its plain name, "iron ingot" for
   * "minecraft:iron_ingot". A stone cost is met by any cobblestone, cobbled
   * deepslate or sandstone, but "cobblestone" is the concrete item a villager
   * or player is asked for: "stone" (smooth stone) is a different item and pays
   * for nothing.
   */
  public static String describe(Item wanted) {
    switch (kindOf(wanted)) {
      case LOGS:
        return "logs";
      case PLANKS:
        return "planks";
      case STONE:
        return "cobblestone";
      case WOOL:
        return "wool";
      default:
        return wanted.toString().replace("minecraft:", "").replace('_', ' ');
    }
  }

  private static int kindTotal(Map<Item, Integer> stock, Kind kind) {
    int total = 0;
    for (Map.Entry<Item, Integer> entry : stock.entrySet()) {
      if (kindOf(entry.getKey()) == kind) {
        total += entry.getValue();
      }
    }
    return total;
  }

  private static void addMissing(List<ItemStack> missing, ItemStack cost, int count) {
    if (count > 0) {
      missing.add(new ItemStack(cost.getItem(), count));
    }
  }

  private static int total(List<ItemStack> stacks) {
    int total = 0;
    for (ItemStack stack : stacks) {
      total += stack.getCount();
    }
    return total;
  }

  private static int ceilDiv(int a, int b) {
    return (a + b - 1) / b;
  }

  /** Merges a piece into the list, growing an existing stack of the same item. */
  private static void addTo(List<ItemStack> stacks, ItemStack piece) {
    for (ItemStack stack : stacks) {
      if (ItemStack.isSameItemSameComponents(stack, piece)) {
        stack.grow(piece.getCount());
        return;
      }
    }
    stacks.add(piece);
  }

}
