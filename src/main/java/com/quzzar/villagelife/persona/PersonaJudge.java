package com.quzzar.villagelife.persona;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.quzzar.villagelife.configuration.VillagelifeConfig;
import com.quzzar.villagelife.llm.LlmService;
import com.quzzar.villagelife.llm.provider.LlmProvider;
import com.quzzar.villagelife.llm.provider.LlmProvider.CompletionRequest;

/**
 * Scores a generated persona against its intended traits with a CLOUD judge model
 * (issue #77): per trait, is it CONVEYED, CONTRADICTED, or ABSENT in the blurb.
 *
 * <p>A judge, not keyword matching, for two reasons the old scorer got wrong in
 * both directions. Good paraphrase scored ZERO under word matching - "a mountain
 * of a man" never equates to "a true giant" without a synonym list of every
 * metaphor in English. And a genuinely inverted trait ("often sick but never
 * complains" for "never ill") scored the SAME as a faithful one, because keyword
 * presence cannot see contradiction. CONTRADICTED is the bucket that catches the
 * inversion, and the whole reason this needs a model.
 *
 * <p>Runs only in the developer persona audit, on a cloud provider read from the
 * dedicated Persona-judge config keys, independent of the villagers' (typically
 * local) model - a small local model cannot reliably make this judgement, which
 * is the finding that motivated a cloud judge. The {@link #parse} step is a pure
 * function, unit-tested without a network call (see PersonaCommands judgetest).
 */
public final class PersonaJudge {

    private PersonaJudge() {
    }

    /** One trait's fate in the blurb. UNSCORED means the judge did not return a verdict for it. */
    public enum Verdict { CONVEYED, CONTRADICTED, ABSENT, UNSCORED }

    public record TraitVerdict(String trait, Verdict verdict) {
    }

    /**
     * The outcome of judging one persona. {@code ok} is false when the judge could
     * not run or answer; the future from {@link #judge} always completes with one
     * of these rather than throwing.
     */
    public record Result(List<TraitVerdict> verdicts, boolean ok, String error) {

        static Result failed(String error) {
            return new Result(List.of(), false, error);
        }

        public long count(Verdict v) {
            return verdicts.stream().filter(t -> t.verdict() == v).count();
        }

        public int total() {
            return verdicts.size();
        }

        /** Fraction of intended traits the blurb conveys, 0..1; 1.0 for a traitless persona. */
        public double conveyedFraction() {
            return verdicts.isEmpty() ? 1.0 : (double) count(Verdict.CONVEYED) / verdicts.size();
        }
    }

    /** True when a judge key is set, i.e. scoring can run. Blank key = audit runs unscored. */
    public static boolean configured() {
        return !VillagelifeConfig.PersonaJudgeApiKey.isBlank();
    }

    /**
     * Judges one persona's blurb against its intended traits. The returned future
     * always completes: an unconfigured judge, a non-cloud provider name, or a
     * failed cloud call all yield a {@code Result} with {@code ok=false}.
     */
    public static CompletableFuture<Result> judge(List<String> traits, String blurb, String quirk) {
        if (!configured()) {
            return CompletableFuture.completedFuture(
                    Result.failed("judge not configured (set the Persona judge API key)"));
        }
        Optional<LlmProvider> provider = LlmService.cloudProvider(VillagelifeConfig.PersonaJudgeProvider,
                () -> VillagelifeConfig.PersonaJudgeApiKey, () -> VillagelifeConfig.PersonaJudgeModel);
        if (provider.isEmpty()) {
            return CompletableFuture.completedFuture(Result.failed(
                    "judge provider '" + VillagelifeConfig.PersonaJudgeProvider + "' is not a cloud provider"));
        }
        if (traits.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(List.of(), true, ""));
        }
        CompletionRequest request = new CompletionRequest(SYSTEM, buildUser(traits, blurb, quirk), List.of(), 512, 0.0D);
        return provider.get().complete(request)
                .thenApply(raw -> raw.map(text -> parse(text, traits))
                        .orElse(Result.failed("judge returned no answer")));
    }

    static final String SYSTEM =
            "You grade how well a short character description conveys a list of intended traits. For EACH intended "
            + "trait decide exactly one verdict: \"conveyed\" - the description expresses it, including by metaphor or "
            + "paraphrase (\"a mountain of a man\" conveys \"a true giant\"; \"hands like lightning\" conveys "
            + "\"quick-fingered\"); \"contradicted\" - the description states something inconsistent with it "
            + "(\"often sick\" contradicts \"never ill\"); or \"absent\" - it is not expressed at all. Judge MEANING, "
            + "not wording. Reply with ONLY a JSON array, one object per intended trait IN THE SAME ORDER: "
            + "[{\"trait\": \"<the trait>\", \"verdict\": \"conveyed|contradicted|absent\"}]. No prose, no code fence.";

    static String buildUser(List<String> traits, String blurb, String quirk) {
        StringBuilder sb = new StringBuilder("Intended traits:\n");
        for (int i = 0; i < traits.size(); i++) {
            sb.append(i + 1).append(". ").append(traits.get(i)).append('\n');
        }
        sb.append("\nDescription: ").append(blurb);
        if (quirk != null && !quirk.isBlank()) {
            sb.append("\nQuirk: ").append(quirk);
        }
        sb.append("\n\nGrade each intended trait, in order. JSON array only:");
        return sb.toString();
    }

    /**
     * Maps a judge reply to a per-trait {@link Result}. Pure and lenient: it reads
     * the array by position (the prompt asks for one entry per trait in order),
     * falls back to locating the array inside stray text, and marks any trait the
     * judge skipped as {@link Verdict#UNSCORED} rather than dropping it. The
     * returned verdicts use the INTENDED trait text, never the model's echo, so a
     * paraphrased or reordered "trait" field cannot corrupt the mapping.
     */
    public static Result parse(String raw, List<String> traits) {
        JsonArray array = extractArray(raw);
        if (array == null) {
            return Result.failed("could not parse judge JSON array");
        }
        List<TraitVerdict> out = new ArrayList<>();
        for (int i = 0; i < traits.size(); i++) {
            Verdict verdict = Verdict.UNSCORED;
            if (i < array.size() && array.get(i).isJsonObject()) {
                JsonObject obj = array.get(i).getAsJsonObject();
                if (obj.has("verdict") && !obj.get("verdict").isJsonNull()) {
                    verdict = verdictOf(obj.get("verdict").getAsString());
                }
            }
            out.add(new TraitVerdict(traits.get(i), verdict));
        }
        return new Result(out, true, "");
    }

    private static JsonArray extractArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonElement whole = JsonParser.parseString(raw);
            if (whole.isJsonArray()) {
                return whole.getAsJsonArray();
            }
        } catch (RuntimeException ignored) {
            // fall through to substring extraction
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            try {
                JsonElement sliced = JsonParser.parseString(raw.substring(start, end + 1));
                if (sliced.isJsonArray()) {
                    return sliced.getAsJsonArray();
                }
            } catch (RuntimeException ignored) {
                // not recoverable
            }
        }
        return null;
    }

    private static Verdict verdictOf(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("convey")) {
            return Verdict.CONVEYED;
        }
        if (v.startsWith("contradict")) {
            return Verdict.CONTRADICTED;
        }
        if (v.startsWith("absent")) {
            return Verdict.ABSENT;
        }
        return Verdict.UNSCORED;
    }
}
