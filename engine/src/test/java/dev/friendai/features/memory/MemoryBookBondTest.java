package dev.friendai.features.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.friendai.features.social.BondState;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryBookBondTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID GUEST = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final BondState SAVED = new BondState(8, 3, 2, List.of("rescue"), Map.of("diamond", 5));

    @Test
    void aFreshBookHasNoBond() {
        MemoryBook book = new MemoryBook();
        assertEquals(Optional.empty(), book.bond(OWNER));
    }

    @Test
    void aStoredBondComesBackAndOnlyBumpsTheRevisionWhenItChanges() {
        MemoryBook book = new MemoryBook();
        long before = book.revision();
        book.putBond(OWNER, SAVED);

        assertEquals(Optional.of(SAVED), book.bond(OWNER));
        assertTrue(book.revision() > before);
        assertTrue(book.isDirty());
        assertEquals(Optional.empty(), book.bond(GUEST), "a guest reads no bond");

        book.markClean();
        book.putBond(OWNER, SAVED);
        assertFalse(book.isDirty(), "storing the same snapshot again changes nothing");
    }

    @Test
    void aNullStateForgetsTheBond() {
        MemoryBook book = new MemoryBook();
        book.putBond(OWNER, SAVED);
        book.markClean();

        book.putBond(OWNER, null);

        assertEquals(Optional.empty(), book.bond(OWNER));
        assertTrue(book.isDirty(), "forgetting a bond is a change");
    }

    @Test
    void bondsSurviveACaptureAndRecordRoundTrip() {
        MemoryBook book = new MemoryBook();
        book.putBond(OWNER, SAVED);

        MemoryRecord record = MemoryRecord.capture(book);
        assertEquals(MemoryRecord.FORMAT_VERSION, record.formatVersion());
        assertEquals(Map.of(OWNER.toString(), SAVED), record.bonds());

        MemoryBook copy = MemoryBook.fromRecord(record);
        assertEquals(Optional.of(SAVED), copy.bond(OWNER));
        assertEquals(record.revision(), copy.revision());
        assertFalse(copy.isDirty());
    }

    @Test
    void aBondSurvivesTheJsonTheStoreWrites() {
        MemoryBook book = new MemoryBook();
        book.putBond(OWNER, SAVED);
        Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

        String json = gson.toJson(MemoryRecord.capture(book));
        MemoryRecord read = gson.fromJson(json, MemoryRecord.class);

        assertEquals(Optional.of(SAVED), MemoryBook.fromRecord(read).bond(OWNER));
    }

    @Test
    void aFormatOneFileReadsWithNoBonds() {
        String json = "{\"formatVersion\":1,\"revision\":3,\"nextId\":1}";

        MemoryRecord record = new Gson().fromJson(json, MemoryRecord.class);

        assertEquals(MemoryRecord.FORMAT_VERSION, record.formatVersion(), "a format 1 file is read as format 2");
        assertTrue(record.bonds().isEmpty(), "a format 1 file has no bonds to read");
        MemoryBook book = MemoryBook.fromRecord(record);
        assertEquals(3, book.revision());
        assertEquals(Optional.empty(), book.bond(OWNER));
    }

    @Test
    void aNewerFormatStaysVisibleSoTheStoreStillRefusesIt() {
        int future = MemoryRecord.FORMAT_VERSION + 1;
        MemoryRecord record =
                new Gson().fromJson("{\"formatVersion\":" + future + ",\"revision\":9}", MemoryRecord.class);
        assertEquals(future, record.formatVersion(), "the store compares this with the current format and refuses it");
    }

    @Test
    void anOwnerIdThatNoLongerParsesIsSkipped() {
        MemoryRecord record = new MemoryRecord(
                MemoryRecord.FORMAT_VERSION,
                1,
                1,
                List.of(),
                List.of(),
                Map.of(),
                Map.of("not-a-uuid", SAVED, OWNER.toString(), SAVED));

        MemoryBook book = MemoryBook.fromRecord(record);

        assertEquals(Optional.of(SAVED), book.bond(OWNER));
        assertEquals(1, book.bonds().size(), "the unparsable owner is dropped");
    }
}
