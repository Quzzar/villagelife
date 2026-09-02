package com.quzzar.villagelife.village;

import java.util.ArrayList;
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
import com.quzzar.villagelife.chat.VillagerText;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;

/**
 * Builds a village's {@link ShelvingPlan} by talking it out. The quartermaster
 * proposes a slot-by-slot partition of the storehouse; a deterministic validator
 * checks it (every slot covered exactly once, no overlaps, every item placed);
 * and if it does not hold, the errors go back to the table and the quartermaster
 * and the village brain take turns correcting it, up to {@link #MAX_ROUNDS}
 * rounds. The first partition that validates wins; if none does, it returns
 * empty and the shelves keep the order they had.
 *
 * <p>The model does the slot arithmetic itself (the design's deliberate choice),
 * which a small model gets wrong; the validator is what makes that safe, since a
 * plan that loses or double-books a slot can never be applied. What the rounds
 * are for is the grouping: which goods belong together, and under what name,
 * is the model's call. Once every item sits in exactly one group, a partition
 * whose slot sums still do not add up is not sent back again; the shelves are
 * laid out from those groups by count instead ({@link #layoutByCount}), because
 * arithmetic is bookkeeping, not a decision, and Llama-3.2-3B failed it six
 * rounds running on a real 81-slot storehouse (2026-09-01). Out of rounds with
 * the grouping still incomplete, the last grouping given is laid out the same
 * way, with the goods it never named on an "Odds and ends" shelf. Only a run
 * that never parsed a single group ends with no plan. Nothing here blocks a
 * tick: every round rides the background persona lane, and the future completes
 * off the game thread.
 */
public final class QuartermasterPlanner {

  /** Distinct items shown to the model; a longer list makes a small model choose worse. */
  private static final int MAX_ITEMS_SHOWN = 40;

  /** How many correcting turns the pair gets before giving up on a valid partition. */
  private static final int MAX_ROUNDS = 6;

  /** How many offending slots or items an error message lists before trailing off. */
  private static final int MAX_LISTED = 12;

  private static final int PLAN_MAX_TOKENS = 500;
  private static final double TEMPERATURE = 0.3D;

  private static final Pattern NUMBER = Pattern.compile("\\d+");

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
        numberedList(items, snapshot), layout(containers), quartermaster.getFullName(),
        quartermaster.getVillageName());
    Villagelife.LOGGER.info("[quartermaster] {} sits down to shelve {} slots across {} item types",
        context.who(), totalSlots, items.size());
    return converge(context, 0, List.of(), List.of());
  }

  /**
   * One round of the dialogue, then recursion until the grouping holds or the
   * rounds run out. {@code prior} is the last proposal that parsed at all, so a
   * correcting turn always sees a real attempt rather than a blank.
   */
  private static CompletableFuture<Optional<Outcome>> converge(Context context, int round,
      List<RawCategory> prior, List<String> priorErrors) {
    boolean brainSpeaks = round % 2 == 1;
    String system = brainSpeaks
        ? "You are the shared good sense of " + context.village()
            + ", working with your quartermaster " + context.who() + " to shelve the storehouse."
        : "You are " + context.who() + ", quartermaster of " + context.village()
            + ". You keep the storehouse and decide which slots hold what.";
    String user = round == 0 ? openingPrompt(context)
        : fixPrompt(context, render(prior, context.items()), priorErrors);

    String purpose = context.who() + " shelves the storehouse, round " + round;
    return LlmService.get().submitPersona(purpose, system, user, PLAN_MAX_TOKENS, TEMPERATURE).thenCompose(reply -> {
      String raw = reply.orElse("");
      List<RawCategory> proposal = parseCategories(raw, context.items().size());
      List<String> errors = validate(proposal, context.items().size(), context.totalSlots());
      Villagelife.LOGGER.info("[quartermaster] {} round {} ({}): {} groups, {}",
          context.who(), round + 1, brainSpeaks ? "brain" : "quartermaster", proposal.size(),
          errors.isEmpty() ? "valid" : errors.size() + " problem(s): " + String.join("; ", errors));

      if (errors.isEmpty() && !proposal.isEmpty()) {
        Villagelife.LOGGER.info("[quartermaster] {} settled a shelving plan in {} round(s)",
            context.who(), round + 1);
        return CompletableFuture.completedFuture(Optional.of(build(context, proposal, raw)));
      }
      if (!proposal.isEmpty() && everyItemPlacedOnce(proposal, context.items().size())) {
        // The grouping is complete; only the slot sums are off. That part is
        // arithmetic, and arithmetic does not get more rounds.
        Villagelife.LOGGER.info("[quartermaster] {} settled the groups in {} round(s); "
            + "the slot sums did not add up, so the shelves are laid out by count",
            context.who(), round + 1);
        return CompletableFuture.completedFuture(Optional.of(layoutByCount(context, proposal, raw)));
      }
      List<RawCategory> latest = proposal.isEmpty() ? prior : proposal;
      if (round + 1 >= MAX_ROUNDS) {
        if (latest.isEmpty()) {
          Villagelife.LOGGER.info("[quartermaster] {} never gave a readable grouping in {} rounds",
              context.who(), MAX_ROUNDS);
          return CompletableFuture.completedFuture(Optional.empty());
        }
        Villagelife.LOGGER.info("[quartermaster] {} could not finish the grouping in {} rounds; "
            + "keeping the groups given and laying the shelves out by count",
            context.who(), MAX_ROUNDS);
        return CompletableFuture.completedFuture(Optional.of(layoutByCount(context, latest, raw)));
      }
      return converge(context, round + 1, latest, errors);
    });
  }

  /** Whether every numbered item sits in exactly one group: the grouping is done, whatever the slots say. */
  private static boolean everyItemPlacedOnce(List<RawCategory> groups, int itemCount) {
    int[] seen = new int[itemCount + 1];
    for (RawCategory group : groups) {
      for (int number : group.itemNumbers()) {
        if (number >= 1 && number <= itemCount) {
          seen[number]++;
        }
      }
    }
    for (int number = 1; number <= itemCount; number++) {
      if (seen[number] != 1) {
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
    return "Our storehouse " + context.layout() + " It holds these goods:\n" + context.numbered()
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
    return "Our storehouse " + context.layout() + " It holds these goods:\n" + context.numbered()
        + "\nThe plan so far:\n" + priorRender
        + "\nIt is not valid yet:\n" + problems
        + "Fix it and reply with ONLY the corrected JSON, same format:\n" + formatExample()
        + "\nCover every slot from 1 to " + context.totalSlots()
        + " once, with no gaps and no overlaps, and put every item in one group.";
  }

  private static String formatExample() {
    return "{\"groups\":[{\"name\":\"Ores\",\"items\":[1,4],\"slots\":\"1-6\"},"
        + "{\"name\":\"Wood\",\"items\":[2,3],\"slots\":\"7-15\"}],"
        + "\"note\":\"one short sentence, in your own voice\"}";
  }

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

  private static String numberedList(List<Item> items, Map<Item, Integer> counts) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      out.append(i + 1).append(". ").append(displayName(item))
          .append(" (x").append(counts.getOrDefault(item, 0)).append(")\n");
    }
    return out.toString();
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
   * uncovered, and items placed twice or not at all. Empty means valid.
   */
  private static List<String> validate(List<RawCategory> groups, int itemCount, int totalSlots) {
    List<String> errors = new ArrayList<>();
    if (groups.isEmpty()) {
      errors.add("no groups were given");
      return errors;
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
      for (int number : group.itemNumbers()) {
        if (number < 1 || number > itemCount) {
          continue;
        }
        if (itemOwner[number] != 0) {
          errors.add("item " + number + " is placed in two groups");
        } else {
          itemOwner[number] = g + 1;
        }
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

  private static List<RawCategory> parseCategories(String raw, int itemCount) {
    List<RawCategory> groups = new ArrayList<>();
    try {
      JsonElement root = JsonParser.parseString(extractObject(raw));
      if (!root.isJsonObject()) {
        return groups;
      }
      JsonArray array = root.getAsJsonObject().getAsJsonArray("groups");
      if (array == null) {
        return groups;
      }
      for (JsonElement element : array) {
        if (!element.isJsonObject()) {
          continue;
        }
        JsonObject group = element.getAsJsonObject();
        String name = group.has("name") ? group.get("name").getAsString() : "";
        List<Integer> numbers = new ArrayList<>();
        JsonArray items = group.getAsJsonArray("items");
        if (items != null) {
          for (JsonElement number : items) {
            try {
              numbers.add(number.getAsInt());
            } catch (RuntimeException ignored) {
              // a non-numeric entry is simply skipped
            }
          }
        }
        int[] range = parseRange(group.has("slots") ? group.get("slots").getAsString() : "");
        groups.add(new RawCategory(name, numbers, range[0], range[1]));
      }
    } catch (RuntimeException e) {
      Villagelife.LOGGER.debug("[quartermaster] could not parse a plan reply: {}", e.getMessage());
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

  private static String extractNote(String raw) {
    try {
      JsonElement root = JsonParser.parseString(extractObject(raw));
      if (root.isJsonObject() && root.getAsJsonObject().has("note")) {
        return root.getAsJsonObject().get("note").getAsString();
      }
    } catch (RuntimeException ignored) {
      // no note is fine; the caller supplies a default
    }
    return "";
  }

  /** The outermost {@code {...}} in a reply, so leading chatter does not break parsing. */
  private static String extractObject(String raw) {
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    return start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
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
