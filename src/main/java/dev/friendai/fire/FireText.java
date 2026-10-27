package dev.friendai.fire;

import dev.friendai.pathing.Pos;

/** The English the model reads: a plan, a refusal and a finished report. Player-facing strings are keys. */
public final class FireText {

    private FireText() {}

    /** One line per step, so the model can say what it is about to do before it walks anywhere. */
    public static String describe(ExtinguishPlan plan) {
        StringBuilder out = new StringBuilder();
        for (ExtinguishStep step : plan.steps()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(describe(step));
        }
        for (String note : plan.notes()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(note);
        }
        return out.toString();
    }

    /** The sentence a task reports when it stopped short. */
    public static String gaveUp(String reason) {
        return "I can't put it out: " + reason + ".";
    }

    /** The sentence a task reports when it finished. */
    public static String done(int cells) {
        return "put out " + cells + (cells == 1 ? " fire" : " fires");
    }

    private static String describe(ExtinguishStep step) {
        return switch (step) {
            case ExtinguishStep.PlaceWater water -> "pour a water bucket at " + at(water.pos())
                    + " and take the water back";
            case ExtinguishStep.DouseCampfire campfire -> "pour water on the campfire at "
                    + at(campfire.pos());
            case ExtinguishStep.RecoverBucket bucket -> "take the source back with an empty bucket at "
                    + at(bucket.pos());
            case ExtinguishStep.EnterWater water -> "step into the water at " + at(water.pos());
            case ExtinguishStep.StandInPowderSnow snow -> "place powder snow at " + at(snow.pos())
                    + " and stand in it";
            case ExtinguishStep.BreakFire fire -> "break the fire at " + at(fire.pos()) + " by hand";
            case ExtinguishStep.GiveUp giveUp -> "give up: " + giveUp.reason();
        };
    }

    private static String at(Pos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }
}
