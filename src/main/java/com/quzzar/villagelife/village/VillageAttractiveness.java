package com.quzzar.villagelife.village;

import com.quzzar.villagelife.configuration.VillagelifeConfig;

import net.minecraft.util.Mth;

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
        float deathImpact, float hurtImpact, float shortageImpact,
        double base, double foodComponent, double bedComponent, double homelessComponent,
        double deathComponent, double hurtComponent, double shortageComponent) {

    public enum Status {
        GROWING, HOLDING, DECLINING
    }

    public static VillageAttractiveness compute(int population, int foodCount, int totalBeds, int freeBeds,
            int homelessCount, float deathImpact, float hurtImpact, float shortageImpact) {

        double base = VillagelifeConfig.AttractivenessBase;

        double foodPerCapita = (double) foodCount / Math.max(population, 1);
        double foodComponent = Math.min(foodPerCapita / VillagelifeConfig.AttractivenessFoodTargetPerCapita, 1.0)
                * VillagelifeConfig.AttractivenessFoodMax;

        double bedComponent = Math.min((double) freeBeds / VillagelifeConfig.AttractivenessFreeBedsTarget, 1.0)
                * VillagelifeConfig.AttractivenessFreeBedsMax;

        double homelessFraction = population > 0 ? (double) homelessCount / population : 0.0;
        double homelessComponent = -homelessFraction * VillagelifeConfig.AttractivenessHomelessMax;

        double deathComponent = -deathImpact * VillagelifeConfig.AttractivenessDeathWeight;
        double hurtComponent = -hurtImpact * VillagelifeConfig.AttractivenessHurtWeight;
        double shortageComponent = -shortageImpact * VillagelifeConfig.AttractivenessShortageWeight;

        return new VillageAttractiveness(population, foodCount, totalBeds, freeBeds, homelessCount,
                deathImpact, hurtImpact, shortageImpact,
                base, foodComponent, bedComponent, homelessComponent,
                deathComponent, hurtComponent, shortageComponent);
    }

    public double total() {
        return Mth.clamp(base + foodComponent + bedComponent + homelessComponent
                + deathComponent + hurtComponent + shortageComponent, 0.0, 100.0);
    }

    public Status status() {
        double total = total();
        if (total > VillagelifeConfig.AttractivenessArriveThreshold) {
            return Status.GROWING;
        }
        if (total < VillagelifeConfig.AttractivenessEmigrateThreshold) {
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
                        + "  thresholds: grow above %.0f, decline below %.0f",
                villageName, total(), status(),
                base,
                foodComponent, foodCount, foodPerCapita(), VillagelifeConfig.AttractivenessFoodTargetPerCapita,
                bedComponent, freeBeds, totalBeds,
                homelessComponent, homelessCount, population,
                deathComponent, deathImpact,
                hurtComponent, hurtImpact,
                shortageComponent, shortageImpact,
                VillagelifeConfig.AttractivenessArriveThreshold, VillagelifeConfig.AttractivenessEmigrateThreshold);
    }

}
