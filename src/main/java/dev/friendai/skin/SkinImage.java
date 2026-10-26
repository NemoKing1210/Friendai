package dev.friendai.skin;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Checks that bytes are a 64x64 skin PNG and turns them into one canonical PNG. The size is read from the header
 * before any pixel is decoded, so a file that claims a huge image never allocates it.
 */
public final class SkinImage {
    public static final int SIZE = 64;
    public static final int MAX_INPUT = 64 * 1024;
    public static final int MAX_CANONICAL = 24 * 1024;
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public enum Problem {
        TOO_LARGE,
        NOT_PNG,
        WRONG_SIZE,
        RE_ENCODED_TOO_LARGE
    }

    public sealed interface Result permits Valid, Rejected {}

    /** {@code png} is the canonical re-encoded image, without ancillary chunks. */
    public record Valid(byte[] png) implements Result {}

    public record Rejected(Problem problem) implements Result {}

    private SkinImage() {}

    public static Result validate(byte[] input) {
        if (input.length > MAX_INPUT) {
            return new Rejected(Problem.TOO_LARGE);
        }
        if (!hasSignature(input)) {
            return new Rejected(Problem.NOT_PNG);
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return new Rejected(Problem.NOT_PNG);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                if (reader.getWidth(0) != SIZE || reader.getHeight(0) != SIZE) {
                    return new Rejected(Problem.WRONG_SIZE);
                }
                return reencode(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return new Rejected(Problem.NOT_PNG);
        }
    }

    private static Result reencode(BufferedImage decoded) throws IOException {
        BufferedImage argb = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        argb.getGraphics().drawImage(decoded, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(argb, "png", out)) {
            return new Rejected(Problem.NOT_PNG);
        }
        byte[] png = out.toByteArray();
        return png.length > MAX_CANONICAL ? new Rejected(Problem.RE_ENCODED_TOO_LARGE) : new Valid(png);
    }

    private static boolean hasSignature(byte[] input) {
        if (input.length < SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (input[i] != SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }
}
