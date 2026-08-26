package com.quzzar.villagelife.relationships;

import java.util.List;

import com.quzzar.villagelife.Villagelife;
import com.quzzar.villagelife.entities.RealPerson;
import com.quzzar.villagelife.village.Village;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;

/**
 * Dev command for the relationship system (persona map issue #20):
 * /vlrel show <entity> prints every stored pair the person is part of, with
 * each side's effective opinion and the flavor line.
 */
public final class RelationshipCommands {

    private RelationshipCommands() {
    }

    /** Mounted under /vldev by {@link com.quzzar.villagelife.dev.DevCommands}. */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> branch() {
        return Commands.literal("relationships")
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
        Village village = person.getVillage();
        if (village == null) {
            source.sendFailure(Component.literal(person.getFullName() + " belongs to no village."));
            return 0;
        }
        List<RelationshipPair> pairs = village.relationshipsOf(person.getUUID());
        if (pairs.isEmpty()) {
            source.sendSuccess(() -> Component.literal(person.getFullName() + " has no stored relationships."), false);
            return 1;
        }
        StringBuilder out = new StringBuilder(person.getFullName() + " (" + pairs.size() + " relationships):");
        for (RelationshipPair pair : pairs) {
            RealPerson other = village.getPerson(source.getLevel(), pair.other(person.getUUID()));
            String otherName = other != null ? other.getFullName() : pair.other(person.getUUID()).toString();
            out.append("\n- ").append(otherName)
                    .append(": theirs ").append(pair.opinionOf(person.getUUID()))
                    .append(" / other's ").append(pair.opinionOf(pair.other(person.getUUID())))
                    .append(pair.asymmetric() ? " (asymmetric)" : "")
                    .append(pair.flavor().isBlank() ? "" : " - " + pair.flavor());
        }
        String message = out.toString();
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }
}
