package com.quzzar.kithkyn.entities;

import java.util.function.Supplier;

import com.quzzar.kithkyn.Kithkyn;
import com.quzzar.kithkyn.persona.PersonaData;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class KithkynAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister
            .create(NeoForgeRegistries.ATTACHMENT_TYPES, Kithkyn.MODID);

    public static final Supplier<AttachmentType<PersonSocialData>> SOCIAL = ATTACHMENT_TYPES.register("social",
            () -> AttachmentType.builder(() -> PersonSocialData.EMPTY).serialize(PersonSocialData.CODEC).build());

    public static final Supplier<AttachmentType<PersonaData>> PERSONA = ATTACHMENT_TYPES.register("persona",
            () -> AttachmentType.builder(() -> PersonaData.EMPTY).serialize(PersonaData.CODEC).build());

    public static final Supplier<AttachmentType<PersonalLogData>> PERSONAL_LOG = ATTACHMENT_TYPES.register("personal_log",
            () -> AttachmentType.builder(() -> PersonalLogData.EMPTY).serialize(PersonalLogData.CODEC).build());

    public static final Supplier<AttachmentType<com.quzzar.kithkyn.chat.ChatHistoryData>> CHAT_HISTORY = ATTACHMENT_TYPES
            .register("chat_history", () -> AttachmentType
                    .builder(() -> com.quzzar.kithkyn.chat.ChatHistoryData.EMPTY)
                    .serialize(com.quzzar.kithkyn.chat.ChatHistoryData.CODEC).build());

    /** Per-player conversation memory: the summary a new-day chat picks up from. */
    public static final Supplier<AttachmentType<com.quzzar.kithkyn.chat.ChatSummaryData>> CHAT_SUMMARY = ATTACHMENT_TYPES
            .register("chat_summary", () -> AttachmentType
                    .builder(() -> com.quzzar.kithkyn.chat.ChatSummaryData.EMPTY)
                    .serialize(com.quzzar.kithkyn.chat.ChatSummaryData.CODEC).build());

    /** The matters a villager is seeing through (docs/undertakings.md). */
    public static final Supplier<AttachmentType<UndertakingData>> UNDERTAKINGS = ATTACHMENT_TYPES.register(
            "undertakings",
            () -> AttachmentType.builder(() -> UndertakingData.EMPTY).serialize(UndertakingData.CODEC).build());

}
