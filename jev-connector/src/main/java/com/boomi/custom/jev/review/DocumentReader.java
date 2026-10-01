package com.boomi.custom.jev.review;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads an input document as UTF-8 text, rejecting oversized and binary content before it reaches JEV.
 */
public final class DocumentReader {

    private DocumentReader() {
    }

    public static String readText(InputStream in, long maxBytes) throws IOException, InvalidInputException {
        byte[] bytes = readLimited(in, maxBytes);
        int offset = hasUtf8Bom(bytes) ? 3 : 0;
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                throw new InvalidInputException(
                        "Document appears to be binary (contains NUL bytes). Only text-based documents are supported.");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidInputException(
                    "Document is not valid UTF-8 text. Only text-based documents are supported.", e);
        }
    }

    private static byte[] readLimited(InputStream in, long maxBytes) throws IOException, InvalidInputException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (maxBytes > 0 && total > maxBytes) {
                throw new InvalidInputException(
                        "Document exceeds the maximum size of " + (maxBytes / 1024) + " KB");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean hasUtf8Bom(byte[] b) {
        return b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF;
    }
}
