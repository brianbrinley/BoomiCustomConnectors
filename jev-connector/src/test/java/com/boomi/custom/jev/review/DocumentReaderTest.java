package com.boomi.custom.jev.review;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DocumentReaderTest {

    private static String read(byte[] bytes, long max) throws Exception {
        return DocumentReader.readText(new ByteArrayInputStream(bytes), max);
    }

    @Test
    public void readsUtf8Text() throws Exception {
        assertEquals("héllo wörld", read("héllo wörld".getBytes(StandardCharsets.UTF_8), 0));
    }

    @Test
    public void stripsByteOrderMark() throws Exception {
        byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertEquals("hi", read(withBom, 0));
    }

    @Test
    public void rejectsBinaryContent() throws Exception {
        assertRejected(new byte[] {'%', 'P', 'D', 'F', 0, 1, 2}, 0, "binary");
    }

    @Test
    public void rejectsInvalidUtf8() throws Exception {
        assertRejected(new byte[] {'a', (byte) 0xC3, (byte) 0x28}, 0, "UTF-8");
    }

    @Test
    public void rejectsOversizedDocument() throws Exception {
        assertRejected(new byte[2048], 1024, "maximum size");
    }

    private static void assertRejected(byte[] bytes, long max, String messagePart) throws Exception {
        try {
            read(bytes, max);
            fail("expected rejection");
        } catch (InvalidInputException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
