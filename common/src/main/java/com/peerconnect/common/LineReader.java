/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Reads ONE FRAME = ONE LINE of UTF-8 text terminated by '\n' (JSON Lines). Frames longer than
 * the limit and frames that are not valid UTF-8 are reported with a recoverable {@link FrameException}
 * AFTER the rest of the line has been consumed, so the stream stays aligned on frame boundaries.
 */
public final class LineReader {
    public static final class FrameException extends IOException {
        public final String code;
        public FrameException(String code, String msg) { super(msg); this.code = code; }
    }

    private final InputStream in;
    private final int maxBytes;

    public LineReader(InputStream in, int maxBytes) {
        this.in = new BufferedInputStream(in, 8192);
        this.maxBytes = maxBytes;
    }

    /** @return the next line (without line terminator), or null on EOF. */
    public String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
        boolean tooLong = false, any = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (!any) return null;          // clean EOF
                throw new IOException("EOF inside a frame"); // truncated frame
            }
            any = true;
            if (b == '\n') break;
            if (!tooLong) {
                if (buf.size() >= maxBytes) tooLong = true; else buf.write(b);
            }
        }
        if (tooLong) throw new FrameException(Protocol.E_FRAME_TOO_LARGE, "Frame exceeds " + maxBytes + " bytes");
        byte[] bytes = buf.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, len)).toString();
        } catch (CharacterCodingException e) {
            throw new FrameException(Protocol.E_BAD_ENCODING, "Frame is not valid UTF-8");
        }
    }
}
