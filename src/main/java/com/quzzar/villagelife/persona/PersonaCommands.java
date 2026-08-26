package com.quzzar.villagelife.persona;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.llm.LlmService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Dev/audit commands for the persona system (persona map issue #6). Registered
 * from the persona package to keep this out of VillagelifeCommands' lane.
 *
 * /vlpersona audit <count>  - spawn N villagers through the full
 *                             generate-before-spawn pipeline and dump a report
 * /vlpersona show <entity>  - print the persona an existing person carries
 */
@EventBusSubscriber(modid = Villagelife.MODID)
public final class PersonaCommands {

    private PersonaCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("vlpersona")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("audit")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
                                .executes(context -> audit(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("show")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> show(context.getSource(),
                                        EntityArgument.getEntity(context, "target"))))));
    }

    private static int audit(CommandSourceStack source, int count) {
        if (!LlmService.get().isReady()) {
            source.sendFailure(Component.literal(
                    "LLM worker is not ready (status: " + LlmService.get().getStatus() + "). Try /vlbrain load."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Persona audit: generating " + count + " villagers (personas generate before each spawn)..."), true);
        new PersonaAuditRun(source, count).start();
        return 1;
    }

    private static int show(CommandSourceStack source, net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof RealPerson person)) {
            source.sendFailure(Component.literal("Target is not a villagelife person."));
            return 0;
        }
        PersonaData persona = PersonaService.get(person);
        if (persona.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getFullName() + " has no persona."), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal(
                person.getFullName() + "\nBLURB: " + persona.blurb() + "\nQUIRK: " + persona.quirk()
                        + "\n(" + persona.model() + ", " + persona.generationMs() + " ms, prompt v"
                        + persona.promptVersion() + ")"),
                false);
        return 1;
    }
}
