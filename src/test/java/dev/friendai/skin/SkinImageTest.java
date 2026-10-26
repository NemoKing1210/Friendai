package dev.friendai.skin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SkinImageTest {
    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(7);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFF000000 | random.nextInt(0x1000000));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeInt(out, data.length);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        writeInt(out, (int) crc.getValue());
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** Inserts a chunk right after the 8-byte signature and the 25-byte IHDR chunk. */
    private static byte[] withChunkAfterHeader(byte[] png, byte[] chunk) {
        int at = 8 + 25;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, at);
        out.writeBytes(chunk);
        out.write(png, at, png.length - at);
        return out.toByteArray();
    }

    @Test
    void validPngIsReEncodedAndAccepted() throws IOException {
        SkinImage.Result result = SkinImage.validate(png(64, 64));

        SkinImage.Valid valid = assertInstanceOf(SkinImage.Valid.class, result);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(valid.png()));
        assertEquals(64, decoded.getWidth());
        assertEquals(64, decoded.getHeight());
    }

    @Test
    void rejectsWrongSize() throws IOException {
        assertEquals(new SkinImage.Rejected(SkinImage.Problem.WRONG_SIZE), SkinImage.validate(png(32, 32)));
        assertEquals(new SkinImage.Rejected(SkinImage.Problem.WRONG_SIZE), SkinImage.validate(png(64, 32)));
    }

    @Test
    void rejectsNonPng() {
        byte[] noise = new byte[200];
        new Random(1).nextBytes(noise);

        assertEquals(new SkinImage.Rejected(SkinImage.Problem.NOT_PNG), SkinImage.validate(noise));
        assertEquals(new SkinImage.Rejected(SkinImage.Problem.NOT_PNG), SkinImage.validate(new byte[0]));
    }

    @Test
    void rejectsOversizedInput() {
        byte[] big = new byte[SkinImage.MAX_INPUT + 1];

        assertEquals(new SkinImage.Rejected(SkinImage.Problem.TOO_LARGE), SkinImage.validate(big));
    }

    @Test
    void stripsAncillaryChunks() throws IOException {
        byte[] plain = png(64, 64);
        byte[] text = new byte[20_000];
        new Random(3).nextBytes(text);
        for (int i = 0; i < text.length; i++) {
            text[i] = (byte) ('a' + (text[i] & 15));
        }
        byte[] padded = withChunkAfterHeader(plain, chunk("tEXt", text));

        SkinImage.Valid valid = assertInstanceOf(SkinImage.Valid.class, SkinImage.validate(padded));

        assertTrue(valid.png().length < padded.length);
        assertFalse(new String(valid.png(), StandardCharsets.ISO_8859_1).contains("tEXt"));
    }

    @Test
    void rejectsHeaderClaimingHugeImage() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        writeInt(header, 20_000);
        writeInt(header, 20_000);
        header.write(new byte[] {8, 6, 0, 0, 0});
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(sig);
        out.writeBytes(chunk("IHDR", header.toByteArray()));
        out.writeBytes(chunk("IEND", new byte[0]));

        assertEquals(new SkinImage.Rejected(SkinImage.Problem.WRONG_SIZE), SkinImage.validate(out.toByteArray()));
    }

    @Test
    void hashIsStableAndSixteenHex() throws IOException {
        byte[] bytes = png(64, 64);

        String hash = SkinHash.of(bytes);

        assertEquals(16, hash.length());
        assertTrue(SkinHash.isValid(hash));
        assertEquals(hash, SkinHash.of(bytes.clone()));
        assertNotEquals(hash, SkinHash.of(png(64, 63)));
        assertFalse(SkinHash.isValid("ABCDEF0123456789"));
        assertFalse(SkinHash.isValid("abc"));
        assertFalse(SkinHash.isValid(null));
        assertArrayEquals(bytes, bytes.clone());
    }
}
