/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.common;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * UDP datagram body (JSON, UTF-8): version, type, messageId, sender, recipient, timestamp, payload.
 * type is CHAT_UDP (payload = text) or ACK_UDP (payload empty, messageId = id being acknowledged).
 */
public record UdpPacket(String type, String messageId, String sender, String recipient, long timestamp, String payload) {
    public static final String CHAT = "CHAT_UDP", ACK = "ACK_UDP";

    public byte[] encode() {
        return Msg.of().put("v", (long) Protocol.VERSION).put("type", type).put("messageId", messageId)
                .put("sender", sender).put("recipient", recipient).put("timestamp", timestamp)
                .put("payload", payload).toJson().getBytes(StandardCharsets.UTF_8);
    }

    /** Strictly decodes and validates a datagram. @throws IllegalArgumentException when anything is wrong. */
    public static UdpPacket decode(byte[] buf, int off, int len) {
        String s;
        try {
            s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(buf, off, len)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("invalid UTF-8");
        }
        Msg m;
        try { m = Msg.parse(s); } catch (Json.JsonException e) { throw new IllegalArgumentException("invalid JSON: " + e.getMessage()); }
        if (m.getLong("v", -1) != Protocol.VERSION) throw new IllegalArgumentException("unsupported version");
        String type = m.getString("type"), id = m.getString("messageId"), snd = m.getString("sender"), rcp = m.getString("recipient");
        String payload = m.getString("payload");
        if (!CHAT.equals(type) && !ACK.equals(type)) throw new IllegalArgumentException("unknown type");
        if (!Protocol.isValidMessageId(id)) throw new IllegalArgumentException("bad messageId");
        if (!Protocol.isValidUsername(snd) || !Protocol.isValidUsername(rcp)) throw new IllegalArgumentException("bad sender/recipient");
        if (!m.has("timestamp") || m.getLong("timestamp", Long.MIN_VALUE) == Long.MIN_VALUE) throw new IllegalArgumentException("bad timestamp");
        if (CHAT.equals(type)) {
            String err = Protocol.validateText(payload);
            if (err != null) throw new IllegalArgumentException(err);
        } else if (payload == null) payload = "";
        return new UdpPacket(type, id, snd, rcp, m.getLong("timestamp", 0), payload);
    }
}
