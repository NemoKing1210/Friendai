package dev.friendai.features.mining.excavation;

/** Short labels for the tool result and the task summary. */
public final class ExcavationText {
    private ExcavationText() {}

    public static String label(ExcavationSpec spec) {
        ExcavationGeometry geometry = spec.geometry();
        return switch (geometry.shape()) {
            case BOX -> "blocks in the box";
            case TUNNEL -> "blocks in the tunnel";
            case STAIR -> "blocks in the stair";
            case SHAFT -> "blocks in the shaft";
            case STRIP -> "blocks in the strip mine";
        };
    }

    public static String describe(ExcavationSpec spec) {
        ExcavationGeometry geometry = spec.geometry();
        String where = geometry.shape() == ExcavationShape.BOX
                ? geometry.x1() + " " + geometry.y1() + " " + geometry.z1() + " to " + geometry.x2() + " "
                        + geometry.y2() + " " + geometry.z2()
                : geometry.x1() + " " + geometry.y1() + " " + geometry.z1() + " "
                        + geometry.shape().name().toLowerCase(java.util.Locale.ROOT) + " "
                        + (geometry.direction() == null
                                ? ""
                                : geometry.direction().name().toLowerCase(java.util.Locale.ROOT))
                        + " " + geometry.length();
        return "excavating " + where + " (cap " + spec.count() + ")";
    }
}
