package com.quzzar.villagelife.village;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.chat.Dialogue;
import com.quzzar.villagelife.chat.VillagerText;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Builds a village's {@link ShelvingPlan} by talking it out. The quartermaster
 * proposes a slot-by-slot partition of the storehouse; a deterministic validator
 * checks it (every slot covered exactly once, no overlaps, every item placed);
 * and a plan is sent back to the table for one correcting round only when it has
 * no usable sorting yet. The first partition that validates wins; a run that never
 * parsed a single group returns empty and the shelves keep the order they had.
 *
 * <p>The model does the slot arithmetic itself (the design's deliberate choice),
 * which a small model gets wrong; the validator is what makes that safe, since a
 * plan that loses or double-books a slot can never be applied. What a correcting
 * round is for is the grouping: which goods belong together, and under what name,
 * is the model's call. But only a reply with nothing to keep is sent back - an
 * empty one, or one that dumped every good into a single group. Any real attempt
 * is taken as it stands: its groups are kept, the goods it never named go on a
 * trailing "Odds and ends" catch-all, and the shelves are laid out by count
 * ({@link #layoutByCount}). Slot sums that do not add up are never sent back
 * either - arithmetic is bookkeeping, not a decision, and Llama-3.2-3B failed it
 * six rounds running on a real 81-slot storehouse (2026-09-01), so the pair now
 * takes at most one correcting round ({@link #MAX_ROUNDS}) and loose goods are
 * shelved as odds and ends rather than ground on for rounds it cannot fix.
 *
 * <p>Each good is listed with its count, the creative-inventory tab it sits in
 * ("Building Blocks", "Ingredients", "Food & Drinks"...) and its item tags
 * ("logs", "c:ores"...): the groupings the game already knows, put in front of
 * the model as facts. What to make of them, which shelves to keep and what to
 * call them, stays the model's call; nothing here sorts by tab or tag itself.
 *
 * <p>Two number spaces share one reply, the goods' numbers and the slots', and
 * Llama-3.2-3B once filled a group's items with slot numbers 1 to 27 and the
 * next with 28 to 81; with sixteen goods that passed as "every item placed".
 * So a group with no items, or a number that is not an item, is an error the
 * model hears about, never a plan.
 *
 * <p>Replies are read one group object at a time rather than as one JSON
 * document, because a small model drops a bracket or tucks the note inside the
 * array often enough that a strict parse threw away whole rounds of usable
 * groups. The format is described in words with a bare template, not shown as
 * a worked example, because the same model copied the example's groups into
 * its answer verbatim. Nothing here blocks a tick: every round rides the
 * background persona lane, and the future completes off the game thread.
 */
public final class QuartermasterPlanner {

  /** Distinct items shown to the model; a longer list makes a small model choose worse. */
  private static final int MAX_ITEMS_SHOWN = 40;

  /**
   * How many turns the pair gets in all: one opening proposal and, only when that
   * proposal has no usable sorting yet (empty, or everything in one group), up to two
   * correcting rounds to sort it. A real attempt with some goods left loose is never
   * sent back - it is shelved as-is with an "Odds and ends" catch-all (see the class
   * note) - so the old six-round grind on slot arithmetic the small model could never
   * do is gone, while a collapsed dump still gets a couple of tries at real sorting.
   */
  private static final int MAX_ROUNDS = 3;

  /**
   * With this many kinds of goods or more, a plan must sort them into at least
   * two groups. One group over everything passes the arithmetic (every slot and
   * every item owned once) and is no shelving plan at all: a correcting turn
   * that gave up and merged the lot was being adopted as a valid answer, which
   * is how a storehouse of eight kinds ended up "All Items, slots 1 to 81".
   */
  private static final int KINDS_THAT_NEED_SORTING = 4;

  /** How many offending slots or items an error message lists before trailing off. */
  private static final int MAX_LISTED = 12;

  /** Tags shown per item, shortest first (the short ones are the general ones: "logs" before "logs_that_burn"). */
  private static final int MAX_TAGS_SHOWN = 8;

  /** Enough for forty goods in a dozen groups; a reply that runs on past this is listing numbers, not planning. */
  private static final int PLAN_MAX_TOKENS = 400;
  private static final double TEMPERATURE = 0.3D;

  private static final Pattern NUMBER = Pattern.compile("\\d+");
  /** One flat JSON object, such as a group; the groups never nest. */
  private static final Pattern FLAT_OBJECT = Pattern.compile("\\{[^{}]*\\}");
  /** The note's string value, wherever in the reply it landed. */
  private static final Pattern NOTE = Pattern.compile("\"note\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  /** A finished plan and the quartermaster's one-line word on how it is arranged. */
  public record Outcome(ShelvingPlan plan, String note) {
  }

  /** One group as the model gave it: a name, the item numbers, and a 1-based inclusive slot range. */
  private record RawCategory(String name, List<Integer> itemNumbers, int slotStart, int slotEnd) {
  }

  /** The shelf for goods the model's grouping never named, when the shelves are laid out by count. */
  private static final String ODDS_AND_ENDS = "Odds and ends";

  /** The fixed facts of one planning run, handed to every round. */
  private record Context(RealPerson quartermaster, List<Item> items, Map<Item, Integer> counts,
      int totalSlots, String numbered, String layout, String who, String village) {
  }

  private QuartermasterPlanner() {
  }

  /**
   * Runs the dialogue for one quartermaster and completes with a validated plan,
   * or empty when the shelves are bare, the LLM is down, or the pair never
   * settled a valid partition. Server-thread safe to call; the result completes
   * on the LLM lane, so hop back before touching the world with it.
   */
  public static CompletableFuture<Optional<Outcome>> plan(RealPerson quartermaster) {
    LlmService llm = LlmService.get();
    List<Container> containers = Storehouse.containers(quartermaster);
    int totalSlots = Storehouse.totalSlots(containers);
    if (totalSlots == 0 || !llm.isReady()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }

    Map<Item, Integer> snapshot = Storehouse.snapshot(quartermaster);
    if (snapshot.isEmpty()) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    List<Item> items = new ArrayList<>(snapshot.keySet());
    if (items.size() > MAX_ITEMS_SHOWN) {
      Villagelife.LOGGER.debug("[quartermaster] storehouse has {} item types; showing the first {}",
          items.size(), MAX_ITEMS_SHOWN);
      items = new ArrayList<>(items.subList(0, MAX_ITEMS_SHOWN));
    }

    Context context = new Context(quartermaster, items, snapshot, totalSlots,
        numberedList(items, snapshot, tabsOf(quartermaster, items)), layout(containers),
        quartermaster.getFullName(), quartermaster.getVillageName());
    Villagelife.LOGGER.info("[quartermaster] {} sits down to shelve {} slots across {} item types",
        context.who(), totalSlots, items.size());
    return Dialogue.run(new Shelving(context));
  }

  /**
   * The dialogue as a {@link Dialogue.Protocol}: the quartermaster and the brain
   * take alternate turns proposing and correcting a partition, and the first
   * that holds resolves the talk. The specialized work (parse, validate, lay
   * out) is the same as ever; this only expresses the round loop as a
   * conversation, so a reply is a turn and a settled plan is its resolution.
   *
   * <p>{@code prior} is the last proposal that parsed at all, carried across
   * turns so a correcting round always sees a real attempt rather than a blank;
   * {@code round} is tracked here because the opening prompt differs from the
   * correcting one. The last permitted turn ({@code lastChance}) never sends the
   * groups back again: it lays out whatever grouping stands, by count, or aborts
   * only when no readable grouping was ever given.
   */
  private static final class Shelving implements Dialogue.Protocol<Outcome> {

    private final Context context;
    private List<RawCategory> prior = List.of();
    private List<String> priorErrors = List.of();
    private int round = 0;

    private Shelving(Context context) {
      this.context = context;
    }

    @Override
    public int voices() {
      return 2;
    }

    @Override
    public int maxTurns() {
      return MAX_ROUNDS;
    }

    @Override
    public CompletableFuture<Dialogue.Turn<Outcome>> takeTurn(int speaker, Dialogue.Transcript transcript,
        boolean lastChance) {
      int currentRound = round;
      boolean brainSpeaks = speaker == 1;
      String system = brainSpeaks
          ? "You are the shared good sense of " + context.village()
              + ", working with your quartermaster " + context.who() + " to shelve the storehouse."
          : "You are " + context.who() + ", quartermaster of " + context.village()
              + ". You keep the storehouse and decide which slots hold what.";
      String user = currentRound == 0 ? openingPrompt(context)
          : fixPrompt(context, render(prior, context.items()), priorErrors);

      String purpose = context.who() + " shelves the storehouse, round " + currentRound;
      return LlmService.get().submitPersona(purpose, system, user, PLAN_MAX_TOKENS, TEMPERATURE).thenApply(reply -> {
        String raw = reply.orElse("");
        List<RawCategory> proposal = parseCategories(raw, context.items().size());
        List<String> errors = validate(proposal, context.items().size(), context.totalSlots());
        Villagelife.LOGGER.info("[quartermaster] {} round {} ({}): {} groups, {}",
            context.who(), currentRound + 1, brainSpeaks ? "brain" : "quartermaster", proposal.size(),
            errors.isEmpty() ? "valid" : errors.size() + " problem(s): " + String.join("; ", errors));

        if (errors.isEmpty() && !proposal.isEmpty()) {
          Villagelife.LOGGER.info("[quartermaster] {} settled a shelving plan in {} round(s)",
              context.who(), currentRound + 1);
          return Dialogue.Turn.<Outcome>resolved(build(context, proposal, raw));
        }
        if (!proposal.isEmpty() && groupingHolds(proposal, context.items().size())) {
          // The grouping is complete; only the slot sums are off. That part is
          // arithmetic, and arithmetic does not get more rounds.
          Villagelife.LOGGER.info("[quartermaster] {} settled the groups in {} round(s); "
              + "the slot sums did not add up, so the shelves are laid out by count",
              context.who(), currentRound + 1);
          return Dialogue.Turn.<Outcome>resolved(layoutByCount(context, proposal, raw));
        }
        // A real attempt that only left some goods loose is not sent back for more
        // rounds either: keep the groups the model made, and let layoutByCount shelve
        // the goods it never named on a trailing "Odds and ends" and size every shelf
        // by count. Only an empty reply, or one that dumped everything into a single
        // group (collapsed), still gets a correcting round below - those have no
        // sorting to keep yet. (Aaron 2026-09-03: loose goods become a miscellaneous
        // shelf, not a grind of rounds; the six-round slot arithmetic never worked.)
        if (!proposal.isEmpty() && !collapsed(proposal, context.items().size())) {
          Villagelife.LOGGER.info("[quartermaster] {} settled the groups in {} round(s); "
              + "the goods it did not name go on an \"{}\" shelf, laid out by count",
              context.who(), currentRound + 1, ODDS_AND_ENDS);
          return Dialogue.Turn.<Outcome>resolved(layoutByCount(context, proposal, raw));
        }
        // The next turn corrects a real attempt: a reply that gave nothing, or
        // that gave up the sorting and merged everything into one group, is set
        // aside and the earlier grouping goes back to the table with its problems.
        List<RawCategory> latest = proposal;
        List<String> latestErrors = errors;
        if (proposal.isEmpty()
            || (collapsed(proposal, context.items().size()) && !collapsed(prior, context.items().size())
                && !prior.isEmpty())) {
          latest = prior;
          latestErrors = new ArrayList<>(priorErrors);
          if (!proposal.isEmpty()) {
            latestErrors.add("the last correction merged everything into one group, which is not a plan;"
                + " go back to the groups above and fix them");
          }
        }
        if (lastChance) {
          if (latest.isEmpty()) {
            Villagelife.LOGGER.info("[quartermaster] {} never gave a readable grouping in {} rounds",
                context.who(), MAX_ROUNDS);
            return Dialogue.Turn.<Outcome>abort();
          }
          Villagelife.LOGGER.info("[quartermaster] {} could not finish the grouping in {} rounds; "
              + "keeping the groups given and laying the shelves out by count",
              context.who(), MAX_ROUNDS);
          return Dialogue.Turn.<Outcome>resolved(layoutByCount(context, latest, raw));
        }
        prior = latest;
        priorErrors = latestErrors;
        round = currentRound + 1;
        return Dialogue.Turn.<Outcome>spoke("");
      });
    }
  }

  /** A plan with too few groups for the kinds on the shelves: valid arithmetic, no sorting. */
  private static boolean collapsed(List<RawCategory> groups, int itemCount) {
    return itemCount >= KINDS_THAT_NEED_SORTING && groups.size() < 2;
  }

  /**
   * Whether the grouping is done, whatever the slots say: every numbered item
   * sits in some group, every group holds at least one, and no number is
   * something other than an item. Repeats are allowed; the first group to name
   * an item keeps it.
   */
  private static boolean groupingHolds(List<RawCategory> groups, int itemCount) {
    if (collapsed(groups, itemCount)) {
      return false; // one group is arithmetic, not sorting; it gets no shortcut either
    }
    boolean[] seen = new boolean[itemCount + 1];
    for (RawCategory group : groups) {
      boolean holdsOne = false;
      for (int number : group.itemNumbers()) {
        if (number < 1 || number > itemCount) {
          return false;
        }
        seen[number] = true;
        holdsOne = true;
      }
      if (!holdsOne) {
        return false;
      }
    }
    for (int number = 1; number <= itemCount; number++) {
      if (!seen[number]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Lays the model's groups out across the shelves by arithmetic. Each group, in
   * the order given, gets a contiguous run sized to the stacks its goods make
   * today, and the spare slots are shared out in proportion so the fuller
   * shelves get the more headroom. An item claimed twice stays with the first
   * group to name it; goods no group named go to a trailing "Odds and ends"
   * shelf, so the plan accounts for everything on the shelves and is not redrawn
   * tomorrow for the same goods. Every slot is covered exactly once.
   */
  private static Outcome layoutByCount(Context context, List<RawCategory> groups, String raw) {
    List<String> names = new ArrayList<>();
    List<List<Item>> members = new ArrayList<>();
    boolean[] placed = new boolean[context.items().size()];
    for (RawCategory group : groups) {
      List<Item> held = new ArrayList<>();
      for (int number : group.itemNumbers()) {
        if (number >= 1 && number <= placed.length && !placed[number - 1]) {
          placed[number - 1] = true;
          held.add(context.items().get(number - 1));
        }
      }
      if (!held.isEmpty()) {
        names.add(cleanName(group.name(), names.size()));
        members.add(held);
      }
    }
    List<Item> loose = new ArrayList<>();
    for (int i = 0; i < placed.length; i++) {
      if (!placed[i]) {
        loose.add(context.items().get(i));
      }
    }
    if (!loose.isEmpty()) {
      names.add(ODDS_AND_ENDS);
      members.add(loose);
    }

    int shelves = members.size();
    int[] needed = new int[shelves];
    int neededInAll = 0;
    for (int i = 0; i < shelves; i++) {
      for (Item item : members.get(i)) {
        int count = context.counts().getOrDefault(item, 0);
        int perStack = Math.max(1, item.getDefaultMaxStackSize());
        needed[i] += (count + perStack - 1) / perStack;
      }
      needed[i] = Math.max(1, needed[i]);
      neededInAll += needed[i];
    }
    int total = context.totalSlots();
    int[] size = new int[shelves];
    int given = 0;
    for (int i = 0; i < shelves; i++) {
      // Goods came out of these slots, so what they need fits; the rest is headroom.
      size[i] = neededInAll <= total
          ? needed[i] + (total - neededInAll) * needed[i] / neededInAll
          : Math.max(1, total * needed[i] / neededInAll);
      given += size[i];
    }
    size[shelves - 1] += total - given; // rounding lands on the last shelf

    List<ShelvingPlan.Category> categories = new ArrayList<>();
    int at = 0;
    for (int i = 0; i < shelves; i++) {
      List<String> ids = new ArrayList<>();
      for (Item item : members.get(i)) {
        ids.add(idOf(item));
      }
      categories.add(new ShelvingPlan.Category(names.get(i), ids, at, size[i]));
      at += size[i];
    }
    return finish(context, categories, raw);
  }

  // ---- prompts ----

  private static String openingPrompt(Context context) {
    return "Our storehouse " + context.layout() + " It holds these goods, numbered 1 to "
        + context.items().size() + ", each with the count on hand and the slots that takes, "
        + "the kind of thing it is, and the tags the game files it under:\n" + context.numbered()
        + "\nAssign every slot to exactly one group, and every item to exactly one group. "
        + "A group keeps its items in its slots. Reply with ONLY JSON:\n" + formatExample()
        + "\nCover every slot from 1 to " + context.totalSlots()
        + " once, with no gaps and no overlaps.";
  }

  private static String fixPrompt(Context context, String priorRender, List<String> errors) {
    StringBuilder problems = new StringBuilder();
    for (String error : errors) {
      problems.append("- ").append(error).append('\n');
    }
    return "Our storehouse " + context.layout() + " It holds these goods, numbered 1 to "
        + context.items().size() + ":\n" + context.numbered()
        + "\nThe plan so far:\n" + priorRender
        + "\nIt is not valid yet:\n" + problems
        + "Keep these groups and their names, and fix only the problems listed: move an item that is"
        + " placed twice, give each group its own run of slots, and leave no slot unowned."
        + " Do not merge the groups into one and do not start a new plan."
        + " Reply with ONLY the corrected JSON, same format:\n" + formatExample()
        + "\nCover every slot from 1 to " + context.totalSlots()
        + " once, with no gaps and no overlaps, and put every item in exactly one group.";
  }

  /**
   * The reply's shape, described rather than demonstrated: a worked example
   * with real-looking groups came back copied into the plan verbatim.
   */
  private static String formatExample() {
    return "{\"groups\":[{\"name\":\"...\",\"items\":[...],\"slots\":\"...\"}],\"note\":\"...\"}\n"
        + "name is what the group is called; items lists the numbers of the goods in it, from the list above; "
        + "slots is the run of slot numbers the group owns, written first-last; "
        + "note is one short sentence in your own voice.";
  }

  /**
   * The storehouse as one numbered slot space, chest by chest. What sits where
   * today is not mentioned: the tidy re-lays every chest to the plan anyway.
   * The model divides the slots however it likes; a group that spans two
   * chests is as valid as one that does not.
   */
  private static String layout(List<Container> containers) {
    int total = Storehouse.totalSlots(containers);
    StringBuilder out = new StringBuilder("has " + containers.size() + " chest(s), " + total
        + " slots in total, numbered 1 to " + total + " (");
    int at = 1;
    for (int c = 0; c < containers.size(); c++) {
      int size = containers.get(c).getContainerSize();
      out.append("chest ").append(c + 1).append(" is slots ").append(at).append(" to ").append(at + size - 1);
      at += size;
      out.append(c == containers.size() - 1 ? ")." : ", ");
    }
    return out.toString();
  }

  private static String numberedList(List<Item> items, Map<Item, Integer> counts, Map<Item, String> tabs) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      int count = counts.getOrDefault(item, 0);
      int perStack = Math.max(1, item.getDefaultMaxStackSize());
      int stacks = (count + perStack - 1) / perStack;
      out.append(i + 1).append(". ").append(displayName(item))
          .append(" (x").append(count).append(", ").append(stacks).append(stacks == 1 ? " stack)" : " stacks)");
      String tab = tabs.get(item);
      if (tab != null) {
        out.append(", kind ").append(tab);
      }
      List<String> tags = tagsOf(item);
      if (!tags.isEmpty()) {
        out.append(", tags ").append(String.join(", ", tags));
      }
      out.append('\n');
    }
    return out.toString();
  }

  /**
   * Each listed item's creative-inventory tab, by its display name, built from
   * the world's enabled features so it matches what a player would see. An item
   * in several tabs keeps the first in registry order (Building Blocks before
   * Ingredients); an item in none gets no entry.
   */
  private static Map<Item, String> tabsOf(RealPerson quartermaster, List<Item> items) {
    Map<Item, String> out = new HashMap<>();
    Level level = quartermaster.level();
    CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), false, level.registryAccess());
    for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
      if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
        continue;
      }
      String name = tab.getDisplayName().getString();
      for (ItemStack stack : tab.getDisplayItems()) {
        if (items.contains(stack.getItem())) {
          out.putIfAbsent(stack.getItem(), name);
        }
      }
    }
    return out;
  }

  /**
   * The item's tags as short names, the vanilla namespace dropped and any other
   * kept ("logs", "c:ores"), shortest first and capped, since the short ones
   * are the general ones and a forty-tag list would drown the rest of the line.
   */
  private static List<String> tagsOf(Item item) {
    List<String> names = new ArrayList<>();
    BuiltInRegistries.ITEM.wrapAsHolder(item).tags().forEach((TagKey<Item> tag) -> {
      String namespace = tag.location().getNamespace();
      names.add(namespace.equals("minecraft") ? tag.location().getPath() : tag.location().toString());
    });
    names.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
    return names.size() > MAX_TAGS_SHOWN ? new ArrayList<>(names.subList(0, MAX_TAGS_SHOWN)) : names;
  }

  private static String render(List<RawCategory> groups, List<Item> items) {
    if (groups.isEmpty()) {
      return "(nothing yet)\n";
    }
    StringBuilder out = new StringBuilder();
    for (RawCategory group : groups) {
      out.append("- ").append(group.name()).append(" (slots ").append(group.slotStart())
          .append(group.slotStart() == group.slotEnd() ? "" : "-" + group.slotEnd()).append("): ");
      List<String> names = new ArrayList<>();
      for (int number : group.itemNumbers()) {
        if (number >= 1 && number <= items.size()) {
          names.add(displayName(items.get(number - 1)));
        }
      }
      out.append(names.isEmpty() ? "(no items)" : String.join(", ", names)).append('\n');
    }
    return out.toString();
  }

  // ---- validation ----

  /**
   * Every way the partition falls short, as plain sentences the model can act on:
   * out-of-range or unreadable slot ranges, slots claimed twice, slots left
   * uncovered, groups with no items, numbers that are not items, and items
   * placed twice or not at all. Empty means valid.
   */
  private static List<String> validate(List<RawCategory> groups, int itemCount, int totalSlots) {
    List<String> errors = new ArrayList<>();
    if (groups.isEmpty()) {
      errors.add("no groups were given");
      return errors;
    }
    if (collapsed(groups, itemCount)) {
      errors.add("one group holding everything is not a shelving plan; sort the goods into at least two"
          + " groups of things that belong together, each with its own run of slots");
    }

    int[] slotOwner = new int[totalSlots + 1];
    int[] itemOwner = new int[itemCount + 1];
    for (int g = 0; g < groups.size(); g++) {
      RawCategory group = groups.get(g);
      if (group.slotStart() < 1 || group.slotEnd() > totalSlots || group.slotStart() > group.slotEnd()) {
        errors.add("group \"" + group.name() + "\" has a bad slot range; use two numbers between 1 and "
            + totalSlots);
        continue;
      }
      for (int slot = group.slotStart(); slot <= group.slotEnd(); slot++) {
        if (slotOwner[slot] != 0) {
          errors.add("slot " + slot + " is claimed by two groups");
        } else {
          slotOwner[slot] = g + 1;
        }
      }
      List<Integer> notItems = new ArrayList<>();
      int held = 0;
      for (int number : group.itemNumbers()) {
        if (number < 1 || number > itemCount) {
          notItems.add(number);
          continue;
        }
        held++;
        if (itemOwner[number] != 0) {
          errors.add("item " + number + " is placed in two groups");
        } else {
          itemOwner[number] = g + 1;
        }
      }
      if (!notItems.isEmpty()) {
        errors.add("group \"" + group.name() + "\" lists numbers that are not items: " + listOf(notItems)
            + "; the goods are numbered 1 to " + itemCount + ", slots are a separate list");
      }
      if (held == 0) {
        errors.add("group \"" + group.name() + "\" has no items in it");
      }
    }

    List<Integer> gaps = new ArrayList<>();
    for (int slot = 1; slot <= totalSlots; slot++) {
      if (slotOwner[slot] == 0) {
        gaps.add(slot);
      }
    }
    if (!gaps.isEmpty()) {
      errors.add("these slots are not assigned to any group: " + listOf(gaps));
    }
    List<Integer> loose = new ArrayList<>();
    for (int number = 1; number <= itemCount; number++) {
      if (itemOwner[number] == 0) {
        loose.add(number);
      }
    }
    if (!loose.isEmpty()) {
      errors.add("these items are not in any group: " + listOf(loose));
    }
    return dedupe(errors);
  }

  private static Outcome build(Context context, List<RawCategory> groups, String raw) {
    List<ShelvingPlan.Category> categories = new ArrayList<>();
    for (int i = 0; i < groups.size(); i++) {
      RawCategory group = groups.get(i);
      List<String> ids = new ArrayList<>();
      for (int number : group.itemNumbers()) {
        if (number >= 1 && number <= context.items().size()) {
          ids.add(idOf(context.items().get(number - 1)));
        }
      }
      categories.add(new ShelvingPlan.Category(cleanName(group.name(), i), ids,
          group.slotStart() - 1, group.slotEnd() - group.slotStart() + 1));
    }
    return finish(context, categories, raw);
  }

  /** The plan and the quartermaster's word on it, from whichever layout produced the categories. */
  private static Outcome finish(Context context, List<ShelvingPlan.Category> categories, String raw) {
    ShelvingPlan plan = new ShelvingPlan(categories, context.totalSlots());
    String note = extractNote(raw);
    // A note is villager writing; strip em dashes, and fall back when there is none.
    note = VillagerText.clean(note.isBlank() ? context.who() + " keeps like with like." : note);
    return new Outcome(plan, note);
  }

  // ---- parsing ----

  /**
   * Every group the reply spells out, read one flat object at a time, so a reply
   * whose outer JSON is broken (a bracket dropped, the note tucked inside the
   * array, chatter around the object) still yields the groups it did give. An
   * object without an items list is the note, or noise, and is skipped.
   */
  private static List<RawCategory> parseCategories(String raw, int itemCount) {
    List<RawCategory> groups = new ArrayList<>();
    Matcher matcher = FLAT_OBJECT.matcher(raw);
    while (matcher.find()) {
      String text = matcher.group();
      try {
        JsonElement element = JsonParser.parseString(text);
        if (!element.isJsonObject() || !element.getAsJsonObject().has("items")) {
          continue;
        }
        JsonObject group = element.getAsJsonObject();
        String name = group.has("name") && group.get("name").isJsonPrimitive()
            ? group.get("name").getAsString() : "";
        List<Integer> numbers = new ArrayList<>();
        if (group.get("items").isJsonArray()) {
          JsonArray items = group.getAsJsonArray("items");
          for (JsonElement number : items) {
            try {
              numbers.add(number.getAsInt());
            } catch (RuntimeException ignored) {
              // a non-numeric entry is simply skipped
            }
          }
        }
        String slots = group.has("slots") && group.get("slots").isJsonPrimitive()
            ? group.get("slots").getAsString() : "";
        groups.add(new RawCategory(name, numbers, parseRange(slots)[0], parseRange(slots)[1]));
      } catch (RuntimeException e) {
        Villagelife.LOGGER.debug("[quartermaster] skipped an unreadable group: {}", text);
      }
    }
    return groups;
  }

  /** A slot range from "1-6", "1 to 6", "7", and the like; {@code {-1,-1}} when unreadable. */
  private static int[] parseRange(String slots) {
    Matcher matcher = NUMBER.matcher(slots);
    List<Integer> found = new ArrayList<>();
    while (matcher.find() && found.size() < 2) {
      found.add(Integer.parseInt(matcher.group()));
    }
    if (found.isEmpty()) {
      return new int[] {-1, -1};
    }
    int start = found.get(0);
    int end = found.size() > 1 ? found.get(1) : start;
    return new int[] {Math.min(start, end), Math.max(start, end)};
  }

  /** The note's text, wherever the model put it; empty when there is none. */
  private static String extractNote(String raw) {
    Matcher matcher = NOTE.matcher(raw);
    return matcher.find() ? matcher.group(1).replace("\\\"", "\"") : "";
  }

  private static String cleanName(String name, int ordinal) {
    String trimmed = name == null ? "" : name.strip();
    if (trimmed.isEmpty()) {
      return "Group " + (ordinal + 1);
    }
    String capped = trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
    return VillagerText.clean(capped); // a category name is villager writing; strip em dashes
  }

  private static String listOf(List<Integer> numbers) {
    List<String> shown = new ArrayList<>();
    for (int i = 0; i < numbers.size() && i < MAX_LISTED; i++) {
      shown.add(String.valueOf(numbers.get(i)));
    }
    String joined = String.join(", ", shown);
    return numbers.size() > MAX_LISTED ? joined + ", and more" : joined;
  }

  private static List<String> dedupe(List<String> errors) {
    List<String> out = new ArrayList<>();
    for (String error : errors) {
      if (!out.contains(error)) {
        out.add(error);
      }
    }
    return out;
  }

  private static String displayName(Item item) {
    return item.getDescription().getString();
  }

  private static String idOf(Item item) {
    return BuiltInRegistries.ITEM.getKey(item).toString();
  }
}
