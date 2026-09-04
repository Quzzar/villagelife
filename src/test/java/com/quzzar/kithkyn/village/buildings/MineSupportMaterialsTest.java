package com.quzzar.kithkyn.village.buildings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

class MineSupportMaterialsTest {

  @Test
  void placesTheSameBlockTheMinerSpent() {
    assertEquals(Blocks.DIRT.defaultBlockState(),
        MineSupportMaterials.blockState(new ItemStack(Items.DIRT)));
    assertEquals(Blocks.DEEPSLATE.defaultBlockState(),
        MineSupportMaterials.blockState(new ItemStack(Items.DEEPSLATE)));
  }

  @Test
  void supportTagIncludesDirtAndTheStoneFamilies() throws Exception {
    try (var stream = getClass().getResourceAsStream(
        "/data/kithkyn/tags/item/mine_support_materials.json")) {
      assertTrue(stream != null, "mine support item tag should be packaged");
      var values = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonArray("values").toString();
      assertTrue(values.contains("#minecraft:dirt"), values);
      assertTrue(values.contains("#c:stones"), values);
      assertTrue(values.contains("#c:cobblestones"), values);
      assertTrue(values.contains("#c:sandstone/blocks"), values);
    }
  }
}
