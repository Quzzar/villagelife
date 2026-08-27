# The market, emeralds, and trade

**Decided; the valuation engine is built, trade is not.** Shipped: `economy/ItemValues`
(the authored raw table plus recipe decomposition outward, memoised and cycle-guarded),
`economy/Bank` (one spread multiplier both ways), `economy/VillagePricing` (supply and
demand inside the band), the datapack loader, and `/vldev economy` for inspection.
Not built: the MERCHANT occupation, the market building, the physical emerald treasury,
any trading interface, and any actual transfer of items or emeralds — the engine prices
things, nothing trades yet. The design agreed on
[The market, and what emeralds are for](https://github.com/Quzzar/villagelife/issues/51);
implementation is a separate effort. Sibling docs:
[building-spec.md](building-spec.md) for the market building itself,
[worker-loops.md](worker-loops.md) for the merchant's work cycle.

## Why a market exists at all

A player can already drop items into a village chest, and take them out. The market is not
a priced version of that. Two things give it a reason to exist:

- **Taking without asking becomes theft.** Once wrongdoing is witnessed and costs standing
  (see the theft ticket), the market is the *legitimate* channel.
- **The village pays you.** Its shortages are a paid quest board, and the emeralds you earn
  buy its surplus back. That is a loop; a priced chest is not.

## The bank: a floor under everything

An abstract exchange, always available, with no counterparty: the mod itself. It buys and
sells any valued item for emeralds at a deliberately punishing rate, set by **one
multiplier in both directions** (v1: the bank pays 1/4 of an item's value and charges 4x).

Everything follows from that spread:

- A village drowning in wood and starving for iron can always convert, alone, at a bad
  rate. **This is how biome poverty resolves without a player** (alongside writing variant
  recipes that do not hard-require what a biome lacks).
- **The player is always the better deal.** If the bank pays one emerald for 128 wood, a
  player offering a stack per emerald is worth talking to. The village's willingness to pay
  is bounded by what the bank would give it, and the player trades inside that band.
- The multiplier is the single dial for how harsh the whole economy feels.

## What things are worth

Rates come from **raw materials outward**:

1. **An authored table of raw materials only** (roughly forty entries: log, stone, iron
   ingot, wheat, wool...), anchored around **1 emerald ≈ 1 bread**.
2. **Every crafted item is derived** by decomposing its recipe down to raw materials at
   runtime: memoized, cycle-guarded (iron block ↔ ingots), taking the cheapest recipe path
   so crafting never mints value.
3. **Tag fallbacks** (`c:ingots`, `c:gems`, `c:logs`) value uncraftable modded raws near
   their peers.
4. Anything still unvalued is **not tradeable**.

This is what makes large modpacks work: thousands of items get priced automatically because
almost all of them are craftable from things already priced, and no pack author has to
write a table.

## The price a village asks

The rate table gives an item's *value*; what a village actually offers floats with **its
own supply and demand**, inside the bank's spread. A village with granaries full of wheat
offers little for more of it; a village whose smithy is idle for want of iron pays near the
top of the band. The market system defines the legal rates and trades; the **brain acts
within them** and can voice what the village is short of.

Trades the village makes on its own initiative: **rules propose, the brain approves.**
Arithmetic finds the legal, beneficial moves (surplus above reserve, shortage below need);
the brain picks among them and narrates why in the journal. Nothing waits on inference.

## What is for sale

The village **reserves what its current project and workers need** and offers only what is
above that line. A village can never sell itself into a stall, and the shop stocks genuine
surplus.

## Money

- **Finite treasury**, held as **physical emeralds in the market's chest**: visible,
  giftable, and robbable, consistent with everything else in this project being physical.
- A village **founds broke**. It must sell something to earn its first emerald, so early
  poverty is real and a gift matters most exactly when the village is poorest.
- At zero it can still sell, but cannot buy.

## Gating

- **No market, no trade** — neither the player shop nor bank access.
- The market needs a **staffed MERCHANT**, like every other workplace. Trade competes for
  labour, and a village that loses its merchant stops trading until someone is hired.
- Levels grant different *capabilities*, not bigger numbers:

| Level | Grants |
| --- | --- |
| 1 | **Access**: trade exists at all (player shop and bank) |
| 2 | **Initiative**: the village trades on its own, with no player present |
| 3 | **Rates**: a narrower spread, closer to fair value |

**Initiative is live, and gated on a capability rather than on a level number.** A market
grants `TRADE_INITIATIVE` and the village checks for it, which keeps the gate in the
datapack where every other capability lives (#55) — so moving it to level 2 is a JSON edit
and not a code change. The placeholder market carries it today, because the real market
levels do not exist yet and a gate nothing can pass is a feature nobody can see.

**What an unattended trade does.** Every five village minutes, a village with that
capability and a staffed market weighs the legal moves and makes at most one: it sells
genuine surplus — what it holds, less what its current project and its saved-for goal have
already committed, less a floor it will not sell past — and it buys the one material it
cannot otherwise afford. The rules find the moves, the brain picks among them and says why,
and with no model the rules' own first choice stands.

## Standing

Village standing is **derived from its residents' opinions**, never stored separately, so
nothing can drift out of sync. It shifts the prices you are offered, and at hostile the
village refuses to trade with you at all — which is what gives theft and violence a lasting
cost you feel on every visit.

**The ladder, live, and every rung a config number.** Standing is the average of what the
village's residents think of you, from -100 to 100 — an average rather than a sum, so a town
is not automatically angrier than a hamlet about the same theft, and one furious neighbour
does not speak for everybody.

| At or below | The village | Default |
| --- | --- | --- |
| disliked | charges you over the odds, rising the further you fall, to a worst markup | -10 |
| unwelcome | closes its market to you | -30 |
| shunned | will not talk to you — villagers turn away when you try | -50 |
| hostile | sets its fighters on you | -70 |

Each rung keeps the ones above it, and **every rung is escapable**: what a villager feels
about an outsider fades toward indifference on its own, so staying away and behaving is a
way back. A grudge that cannot be worked off is a permanent tax rather than a punishment.
`/vldev village standing` reports the number and what it currently costs you.
