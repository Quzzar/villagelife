package com.quzzar.kithkyn.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.quzzar.kithkyn.PersonEntityType;
import com.quzzar.kithkyn.entities.AgeStage;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.entities.genetics.AppearanceGenes;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * A controlled visual comparison of every villager age stage.
 *
 * <p>All four preview people share the same appearance genes and default
 * client-side attributes. Only {@link AgeStage} changes, so a screenshot makes
 * model proportions and relative stage scale directly comparable.
 */
public final class AgeLineupScreen extends Screen {

    static final int PREVIEW_SEED = 2_073_418;
    private static final int BACKGROUND_TOP = 0xFF20242A;
    private static final int BACKGROUND_BOTTOM = 0xFF15181C;
    private static final int GUIDE = 0xFF3A4048;
    private static final int BASELINE = 0xFFD5A94E;
    private static final int PRIMARY_TEXT = 0xFFF2F0EA;
    private static final int SECONDARY_TEXT = 0xFF9EA5AE;

    private final List<StagePreview> previews;

    public AgeLineupScreen(ClientLevel level) {
        super(Component.literal("Villager age stages"));
        this.previews = createPreviews(level);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fillGradient(0, 0, width, height, BACKGROUND_TOP, BACKGROUND_BOTTOM);

        graphics.drawCenteredString(font, title, width / 2, 14, PRIMARY_TEXT);
        graphics.drawCenteredString(
                font,
                "Same appearance and attributes; only age changes",
                width / 2,
                27,
                SECONDARY_TEXT);

        int baselineY = height - 47;
        int columnWidth = width / previews.size();
        graphics.fill(14, baselineY, width - 14, baselineY + 1, BASELINE);

        int entityScale = Mth.clamp(width / 8, 52, 76);
        for (int index = 0; index < previews.size(); index++) {
            StagePreview preview = previews.get(index);
            int left = index * columnWidth;
            int right = index == previews.size() - 1 ? width : left + columnWidth;
            int centerX = (left + right) / 2;

            if (index > 0) {
                graphics.fill(left, 43, left + 1, baselineY - 8, GUIDE);
            }
            graphics.fill(centerX - 2, baselineY - 1, centerX + 3, baselineY + 2, BASELINE);

            graphics.enableScissor(left + 3, 40, right - 3, baselineY);
            renderPerson(graphics, centerX, baselineY, entityScale, preview.person());
            graphics.disableScissor();

            graphics.drawCenteredString(
                    font, stageName(preview.stage()), centerX, baselineY + 10, PRIMARY_TEXT);
            graphics.drawCenteredString(
                    font,
                    preview.stage().usesYoungModel() ? "young proportions" : "adult proportions",
                    centerX,
                    baselineY + 22,
                    SECONDARY_TEXT);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static List<StagePreview> createPreviews(ClientLevel level) {
        AppearanceGenes genes = AppearanceGenes.fromLegacySeed(PREVIEW_SEED);
        List<StagePreview> created = new ArrayList<>();
        for (AgeStage stage : AgeStage.values()) {
            RealPerson person = Objects.requireNonNull(
                    PersonEntityType.PERSON.get().create(level),
                    "Could not create an age-lineup preview person");
            person.setAppearanceSeed(PREVIEW_SEED);
            person.setAppearanceGenes(genes);
            person.setLifeStage(stage);
            created.add(new StagePreview(stage, person));
        }
        return List.copyOf(created);
    }

    /** Draws every stage from the same floor line and at the same camera scale. */
    private static void renderPerson(
            GuiGraphics graphics,
            int centerX,
            int baselineY,
            int entityScale,
            RealPerson person) {
        person.yBodyRot = 180.0F;
        person.setYRot(180.0F);
        person.setXRot(0.0F);
        person.yHeadRot = 180.0F;
        person.yHeadRotO = 180.0F;

        Quaternionf camera = new Quaternionf();
        Quaternionf pose = new Quaternionf().rotationZ((float) Math.PI).mul(camera);
        InventoryScreen.renderEntityInInventory(
                graphics,
                centerX,
                baselineY,
                entityScale,
                new Vector3f(),
                pose,
                camera,
                person);
    }

    private static String stageName(AgeStage stage) {
        String lower = stage.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private record StagePreview(AgeStage stage, RealPerson person) {
    }
}
