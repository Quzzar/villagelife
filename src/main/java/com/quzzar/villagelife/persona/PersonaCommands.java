package com.quzzar.villagelife.persona;

import com.quzzar.villagelife.entities.RealPerson;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * Dev commands for the persona system (persona map issue #6). Registered from
 * the persona package to keep this out of VillagelifeCommands' lane.
 *
 * /vldev persona show <entity>  - print the persona an existing person carries
 */
public final class PersonaCommands {

    private PersonaCommands() {
    }

    /** Mounted under /vldev by {@link com.quzzar.villagelife.dev.DevCommands}. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("persona")
                .then(Commands.literal("show")
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(context -> show(context.getSource(),
                                        EntityArgument.getEntity(context, "target")))));
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
