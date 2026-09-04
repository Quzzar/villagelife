package com.quzzar.kithkyn.dev;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.configuration.KithkynConfig;
import com.quzzar.kithkyn.economy.EconomyCommands;
import com.quzzar.kithkyn.persona.PersonaCommands;
import com.quzzar.kithkyn.relationships.RelationshipCommands;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Everything under {@code /kkdev}: scaffolding, diagnostics, and stand-ins for
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
 * {@code /kithkyn status|load} and {@code /kithkyn create-village}.
 */
@EventBusSubscriber(modid = Kithkyn.MODID)
public final class DevCommands {

  private DevCommands() {
  }

  @SubscribeEvent
  public static void onRegisterCommands(RegisterCommandsEvent event) {
    if (!KithkynConfig.DeveloperCommands) {
      return;
    }
    event.getDispatcher().register(Commands.literal("kkdev")
        .requires(source -> source.hasPermission(2))
        .then(AppearanceCommands.branch())
        .then(FamilyCommands.branch())
        .then(EconomyCommands.branch())
        .then(PersonaCommands.branch())
        .then(RelationshipCommands.branch())
        .then(com.quzzar.kithkyn.entities.UndertakingCommands.branch())
        .then(com.quzzar.kithkyn.llm.LlmEvents.devBranch())
        .then(com.quzzar.kithkyn.events.KithkynCommands.devBranch()));
    Kithkyn.LOGGER.info("Developer commands are ON (/kkdev). Turn them off before shipping.");
  }
}
