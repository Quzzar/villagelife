package com.quzzar.villagelife.dev;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.economy.EconomyCommands;
import com.quzzar.villagelife.persona.PersonaCommands;
import com.quzzar.villagelife.relationships.RelationshipCommands;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Everything under {@code /vldev}: scaffolding, diagnostics, and stand-ins for
 * interfaces that do not exist yet. Registered only when the developer-commands
 * config flag is on, which is off by default, so a player never sees any of it.
 *
 * The point of one root in one class is that the boundary between a real
 * feature and a temporary crutch stays impossible to blur, and shipping means
 * deleting this package rather than auditing five files for what was scaffolding
 * all along. Anything here is expected to die: when a command's real interface
 * arrives (a market screen, a village that founds itself), delete the branch it
 * stood in for.
 *
 * Player- and operator-facing commands live with their own systems, not here:
 * {@code /vlbrain status|load} and {@code /villagelife create-village}.
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public final class DevCommands {

  private DevCommands() {
  }

  @SubscribeEvent
  public static void onRegisterCommands(RegisterCommandsEvent event) {
    if (!VillagelifeConfig.DeveloperCommands) {
      return;
    }
    event.getDispatcher().register(Commands.literal("vldev")
        .requires(source -> source.hasPermission(2))
        .then(EconomyCommands.branch())
        .then(PersonaCommands.branch())
        .then(RelationshipCommands.branch())
        .then(com.quzzar.villagelife.entities.UndertakingCommands.branch())
        .then(com.quzzar.villagelife.llm.LlmEvents.devBranch())
        .then(com.quzzar.villagelife.events.VillagelifeCommands.devBranch()));
    Villagelife.LOGGER.info("Developer commands are ON (/vldev). Turn them off before shipping.");
  }
}
