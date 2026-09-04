package com.quzzar.kithkyn.dev;

import java.util.List;
import java.util.UUID;
import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.PersonEntityType;
import com.quzzar.kithkyn.entities.RealPerson;
import com.quzzar.kithkyn.persona.PersonaData;
import com.quzzar.kithkyn.persona.PersonaService;
import com.quzzar.kithkyn.village.Occupation;
import com.quzzar.kithkyn.village.Village;
import com.quzzar.kithkyn.village.VillageManager;
import com.quzzar.kithkyn.village.buildings.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** External execution fixture: selection and commitment are explicit; all subsequent work uses real AI ticks. */
@EventBusSubscriber(modid=Kithkyn.MODID)
public final class RedevelopmentExecutionProbe {
  private static long started=-1;
  private static UUID sourceId;
  private static boolean finished;

  @SubscribeEvent
  public static void tick(ServerTickEvent.Post event) {
    if (!Boolean.getBoolean("kithkyn.redevelopment.execution") || finished) return;
    var server=event.getServer();
    var level=server.overworld();
    Village village=VillageManager.get(level).getVillages().values().stream().findFirst().orElse(null);
    if (village==null) return;
    try {
      if (started<0) {
        if (village.getPopulation().stream().anyMatch(id -> !(level.getEntity(id) instanceof RealPerson))) return;
        var source=village.getBuildings().stream().filter(b -> b.getName().equals("house_plains_1")).findFirst().orElseThrow();
        sourceId=source.getUUID();
        var ground=BlockPos.of(source.getOriginLocation()).above(source.getInfo().getSink());
        if (!village.devPlaceBuildingAt("house_plains_1",ground.offset(-14,0,0)))
          throw new IllegalStateException("Could not place spare relocation housing");
        for (int i=0;i<3;i++) {
          RealPerson person=PersonEntityType.PERSON.get().create(level);
          BlockPos fire=village.getGatheringPoint();
          person.moveTo(fire.getX()+i,fire.getY(),fire.getZ()+2,0,0);
          person.finalizeSpawn(level,level.getCurrentDifficultyAt(fire),MobSpawnType.COMMAND,null);
          PersonaService.attach(person,new PersonaData("A resident waiting for independent housing.",
              "Keeps tools tidy.","execution fixture",0,1));
          person.setVillage(village.getID());
          person.setVillageName(village.getName());
          person.setOccupation(Occupation.WANDERER);
          level.addFreshEntity(person);
          village.getPopulation().add(person.getUUID());
        }
        var assessed=RedevelopmentPlanner.assess(village,Buildings.getByName("house_plains_2"),source,
            ground.offset(0,0,-2),Rotation.NONE);
        var plan=assessed.plan().orElseThrow(() -> new IllegalStateException(assessed.reason()));
        if (!village.startRedevelopment(new ConstructionChoice(Buildings.getByName(plan.target()),plan.mode(),plan)))
          throw new IllegalStateException("Could not reserve selected plan");
        var project=village.getCurrentProject();
        var pack=new SimpleContainer(36);
        for (ItemStack needed:project.requiredMaterials()) {
          ItemStack paid=village.gatherItemStackFromVillage(needed.copy());
          if (paid.getCount()!=needed.getCount()) throw new IllegalStateException("Not enough fixture material: "+needed);
          while (!paid.isEmpty()) {
            if (!pack.addItem(paid.split(Math.min(paid.getMaxStackSize(),paid.getCount()))).isEmpty())
              throw new IllegalStateException("Fixture pack full");
          }
        }
        if (!project.commitFromBuilder(pack,village)) throw new IllegalStateException("Secured plan did not commit; missing="
            +Materials.shortfall(pack,project.requiredMaterials())+" valid="+RedevelopmentPlanner.stillValid(village,plan));
        RealPerson builder=village.getJobAssignmentsView().entrySet().stream()
            .filter(e -> e.getValue().getOccupation()==Occupation.BUILDER)
            .map(e -> (RealPerson)level.getEntity(e.getKey())).findFirst().orElseThrow();
        builder.moveTo(ground.getX()-1,ground.getY(),ground.getZ()-1,0,0);
        started=level.getGameTime();
        Kithkyn.LOGGER.info("[redevelopment-execution] COMMITTED plan={} blocks={} builder={}",plan.id(),plan.blocks().size(),builder.getFullName());
      }
      long elapsed=level.getGameTime()-started;
      var project=village.getCurrentProject();
      if (project==null && village.getBuilding(sourceId)!=null && village.getBuilding(sourceId).getName().equals("house_plains_2")) {
        long farms=village.getBuildings().stream().filter(b -> b.getName().equals("farm_plains_1")).count();
        String result="PASS elapsedTicks="+elapsed+" farmsRemaining="+farms+" investment="
            +MaterialAmount.describe(village.getBuilding(sourceId).getInvestment());
        if (farms!=1) throw new IllegalStateException("Unexpected surviving farms: "+farms);
        java.nio.file.Files.writeString(java.nio.file.Path.of("redevelopment-execution-result.txt"),result);
        Kithkyn.LOGGER.info("[redevelopment-execution] {}",result);
        finished=true;server.tickRateManager().stopSprinting();server.halt(false);return;
      }
      if (elapsed%1200==0) Kithkyn.LOGGER.info("[redevelopment-execution] elapsed={} phase={} remaining={} blocker={}",
          elapsed,project==null?"none":project.getProgress(),project==null||project.getRedevelopment()==null?-1:project.getRedevelopment().remainingBlocks(),
          project==null?"":project.siteBlocker());
      if (elapsed>=48000) throw new IllegalStateException("Execution did not complete in two game days; phase="+(project==null?"none":project.getProgress()));
      if (!server.tickRateManager().isSprinting()) server.tickRateManager().requestGameToSprint((int)Math.min(1200,48000-elapsed));
    } catch (Exception | AssertionError failure) {
      Kithkyn.LOGGER.error("[redevelopment-execution] FAIL",failure);
      finished=true;server.tickRateManager().stopSprinting();server.halt(false);
    }
  }
}
