/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.model;

/** One chat message in a conversation. status/detail are changed only by the GUI thread (from listener events). */
public final class ChatMessage {
    public final String id, peer, sender, recipient, text;
    public final long timestamp;
    public final Transport transport;
    public final boolean outgoing;
    private volatile MsgStatus status;
    private volatile String detail;

    public ChatMessage(String id, String peer, String sender, String recipient, String text, long timestamp,
                       Transport transport, boolean outgoing, MsgStatus status, String detail) {
        this.id = id; this.peer = peer; this.sender = sender; this.recipient = recipient; this.text = text;
        this.timestamp = timestamp; this.transport = transport; this.outgoing = outgoing; this.status = status; this.detail = detail;
    }
    public MsgStatus status() { return status; }
    public String detail() { return detail; }
    public void setStatus(MsgStatus s, String d) { this.status = s; this.detail = d; }
}
