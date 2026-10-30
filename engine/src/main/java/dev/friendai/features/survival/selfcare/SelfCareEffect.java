package dev.friendai.features.survival.selfcare;

/** One effect on the companion's body, already classified by the game layer that read it. */
public record SelfCareEffect(String id, int amplifier, int durationTicks, EffectMark mark) {

    public boolean harmful() {
        return mark == EffectMark.HARMFUL;
    }

    public boolean beneficial() {
        return mark == EffectMark.BENEFICIAL;
    }

    /** "poison 12 s", for the prompt and for decision reasons. */
    public String toPromptText() {
        if (durationTicks <= 0) {
            return id;
        }
        return id + " " + seconds() + " s";
    }

    /** Whole seconds left, at least one for a running effect. */
    public int seconds() {
        return Math.max(1, durationTicks / 20);
    }
}
