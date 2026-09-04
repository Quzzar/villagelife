package com.quzzar.kithkyn.village;

import com.quzzar.kithkyn.configuration.KithkynConfig;

/**
 * One computation of a village's attractiveness: the 0-100 score answering
 * "would anyone want to move here?", kept with its full per-component
 * breakdown so it can be tuned, debugged, and later narrated.
 *
 * Arrival and emigration wire onto the thresholds in {@link Village}. Player
 * behavior reaches this score only through the hurt and death events; positive
 * player standing deliberately does not (decided on #43): it lives in each
 * villager's personal opinion of the player, shaped in conversation.
 */
public record VillageAttractiveness(
        int population, int foodCount, int totalBeds, int freeBeds, int homelessCount,
        float deathImpact, float hurtImpact, float shortageImpact, float theftImpact,
        double base, double foodComponent, double bedComponent, double homelessComponent,
        double deathComponent, double hurtComponent, double shortageComponent, double theftComponent) {

    public enum Status {
        GROWING, HOLDING, DECLINING
    }

    public static VillageAttractiveness compute(int population, int foodCount, int totalBeds, int freeBeds,
            int homelessCount, float deathImpact, float hurtImpact, float shortageImpact, float theftImpact) {

        double base = KithkynConfig.AttractivenessBase;

        double foodPerCapita = (double) foodCount / Math.max(population, 1);
        double foodComponent = Math.min(foodPerCapita / KithkynConfig.AttractivenessFoodTargetPerCapita, 1.0)
                * KithkynConfig.AttractivenessFoodMax;

        double bedComponent = Math.min((double) freeBeds / KithkynConfig.AttractivenessFreeBedsTarget, 1.0)
                * KithkynConfig.AttractivenessFreeBedsMax;

        double homelessFraction = population > 0 ? (double) homelessCount / population : 0.0;
        double homelessComponent = -homelessFraction * KithkynConfig.AttractivenessHomelessMax;

        double deathComponent = -deathImpact * KithkynConfig.AttractivenessDeathWeight;
        double hurtComponent = -hurtImpact * KithkynConfig.AttractivenessHurtWeight;
        double shortageComponent = -shortageImpact * KithkynConfig.AttractivenessShortageWeight;
        // Being robbed makes a place less appealing to move to, on the same
        // scale as its other griefs and weighted lightest of the three harms.
        double theftComponent = -theftImpact * KithkynConfig.AttractivenessTheftWeight;

        return new VillageAttractiveness(population, foodCount, totalBeds, freeBeds, homelessCount,
                deathImpact, hurtImpact, shortageImpact, theftImpact,
                base, foodComponent, bedComponent, homelessComponent,
                deathComponent, hurtComponent, shortageComponent, theftComponent);
    }

    public double total() {
        return Math.max(0.0D, Math.min(100.0D,
                base + foodComponent + bedComponent + homelessComponent
                    + deathComponent + hurtComponent + shortageComponent + theftComponent));
    }

    public Status status() {
        double total = total();
        if (total > KithkynConfig.AttractivenessArriveThreshold) {
            return Status.GROWING;
        }
        if (total < KithkynConfig.AttractivenessEmigrateThreshold) {
            return Status.DECLINING;
        }
        return Status.HOLDING;
    }

    public double foodPerCapita() {
        return (double) foodCount / Math.max(population, 1);
    }

    /** Multi-line human-readable breakdown for the debug command and log output. */
    public String describe(String villageName) {
        return String.format(
                "Village '%s' attractiveness: %.1f / 100 (%s)%n"
                        + "  base:            %+.1f%n"
                        + "  food:            %+.1f  (%d items, %.1f per head, target %.1f)%n"
                        + "  free beds:       %+.1f  (%d free of %d)%n"
                        + "  homelessness:    %+.1f  (%d of %d people)%n"
                        + "  deaths:          %+.1f  (impact sum %.2f)%n"
                        + "  player violence: %+.1f  (impact sum %.2f)%n"
                        + "  shortages:       %+.1f  (impact sum %.2f)%n"
                        + "  theft:           %+.1f  (impact sum %.2f)%n"
                        + "  thresholds: grow above %.0f, decline below %.0f",
                villageName, total(), status(),
                base,
                foodComponent, foodCount, foodPerCapita(), KithkynConfig.AttractivenessFoodTargetPerCapita,
                bedComponent, freeBeds, totalBeds,
                homelessComponent, homelessCount, population,
                deathComponent, deathImpact,
                hurtComponent, hurtImpact,
                shortageComponent, shortageImpact,
                theftComponent, theftImpact,
                KithkynConfig.AttractivenessArriveThreshold, KithkynConfig.AttractivenessEmigrateThreshold);
    }

}
