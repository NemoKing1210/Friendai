package dev.friendai.features.creative;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure, explicit allowlist for the bounded command syntax exposed by {@code execute_command}. */
public final class CreativeCommandPolicy {
    public record Decision(boolean allowed, String command, String reason) {
        private static Decision allow(String command) {
            return new Decision(true, command, "");
        }

        private static Decision deny(String reason) {
            return new Decision(false, "", reason);
        }
    }

    private static final int MAX_COMMAND_LENGTH = 2_048;
    private static final int MAX_FEEDBACK_LINES = 8;
    private static final int MAX_FEEDBACK_CHARS = 4_096;
    private static final Pattern ITEM_OR_EFFECT = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NUMBER = Pattern.compile("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    private static final Pattern RELATIVE_COORDINATE = Pattern.compile("~(?:-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))?");

    private CreativeCommandPolicy() {}

    public static Decision evaluate(String raw, String ownerName) {
        if (raw == null || raw.isBlank()) return Decision.deny("command is empty");
        if (raw.length() > MAX_COMMAND_LENGTH) return Decision.deny("command exceeds 2048 characters");
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (value < 0x20 || value == 0x7f)
                return Decision.deny("command must be one line without control characters");
        }
        String command = raw.strip();
        if (command.startsWith("/")) command = command.substring(1).stripLeading();
        if (command.startsWith("/") || command.isEmpty()) return Decision.deny("use one optional leading slash");
        if (command.indexOf(';') >= 0
                || command.indexOf('\\') >= 0
                || command.indexOf('"') >= 0
                || command.indexOf('\'') >= 0) {
            return Decision.deny("multiple commands, escapes and quoted command arguments are not allowed");
        }
        String[] args = command.split("\\s+");
        boolean allowed = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "tp", "teleport" -> teleport(args);
            case "time" -> time(args);
            case "weather" -> weather(args);
            case "give" -> give(args, ownerName);
            case "effect" -> effect(args);
            case "enchant" -> enchant(args, ownerName);
            default -> false;
        };
        return allowed ? Decision.allow(command) : Decision.deny("command form is not on the creative allowlist");
    }

    public static int maxFeedbackLines() {
        return MAX_FEEDBACK_LINES;
    }

    public static int maxFeedbackChars() {
        return MAX_FEEDBACK_CHARS;
    }

    private static boolean teleport(String[] args) {
        if (args.length != 5 || !args[1].equals("@s")) return false;
        return coordinate(args[2], 30_000_000) && coordinate(args[3], 2_048) && coordinate(args[4], 30_000_000);
    }

    private static boolean time(String[] args) {
        if (args.length == 3 && args[1].equals("set")) {
            return switch (args[2].toLowerCase(Locale.ROOT)) {
                case "day", "noon", "sunrise", "sunset" -> true;
                default -> integer(args[2], 0, 24_000);
            };
        }
        if (args.length == 3 && args[1].equals("add")) return integer(args[2], 0, 24_000);
        return args.length == 3
                && args[1].equals("query")
                && (args[2].equals("daytime") || args[2].equals("day") || args[2].equals("gametime"));
    }

    private static boolean weather(String[] args) {
        if (args.length < 2
                || args.length > 3
                || !(args[1].equals("clear") || args[1].equals("rain") || args[1].equals("thunder"))) return false;
        return args.length == 2 || integer(args[2], 1, 24_000);
    }

    private static boolean give(String[] args, String ownerName) {
        if (!targetsOwner(args, ownerName)
                || args.length < 3
                || args.length > 4
                || !ITEM_OR_EFFECT.matcher(args[2]).matches()) return false;
        return args.length == 3 || integer(args[3], 1, 64);
    }

    private static boolean effect(String[] args) {
        if (args.length >= 3 && args[1].equals("clear")) {
            return args[2].equals("@s")
                    && (args.length == 3
                            || args.length == 4
                                    && ITEM_OR_EFFECT.matcher(args[3]).matches());
        }
        if (args.length < 4
                || args.length > 7
                || !args[1].equals("give")
                || !args[2].equals("@s")
                || !ITEM_OR_EFFECT.matcher(args[3]).matches()) return false;
        if (args.length >= 5 && !integer(args[4], 1, 24_000)) return false;
        if (args.length >= 6 && !integer(args[5], 0, 255)) return false;
        return args.length < 7 || args[6].equals("true") || args[6].equals("false");
    }

    private static boolean enchant(String[] args, String ownerName) {
        if (!targetsOwner(args, ownerName)
                || args.length < 3
                || args.length > 4
                || !ITEM_OR_EFFECT.matcher(args[2]).matches()) return false;
        return args.length == 3 || integer(args[3], 1, 255);
    }

    private static boolean targetsOwner(String[] args, String ownerName) {
        return ownerName != null && !ownerName.isBlank() && args.length >= 2 && args[1].equalsIgnoreCase(ownerName);
    }

    private static boolean coordinate(String value, double limit) {
        boolean relative = value.startsWith("~");
        String numeric = relative ? value.substring(1) : value;
        if (relative && numeric.isEmpty()) return true;
        if (!NUMBER.matcher(numeric).matches()) return false;
        try {
            double coordinate = Double.parseDouble(numeric);
            return Double.isFinite(coordinate) && Math.abs(coordinate) <= limit;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean integer(String value, int min, int max) {
        if (!value.matches("[0-9]+")) return false;
        try {
            int number = Integer.parseInt(value);
            return number >= min && number <= max;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
