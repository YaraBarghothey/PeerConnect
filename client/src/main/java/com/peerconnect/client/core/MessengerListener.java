/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.core;

import com.peerconnect.client.model.ChatMessage;
import com.peerconnect.client.model.MsgStatus;
import com.peerconnect.client.model.TcpState;
import com.peerconnect.common.UserInfo;
import java.util.List;

/** Events from the networking core. Called on NETWORK threads: a GUI must re-post them to the Swing EDT. */
public interface MessengerListener {
    default void onTcpState(TcpState state, String detail) {}
    default void onUdpState(String text, int localPort) {}
    default void onUsers(List<UserInfo> users) {}
    default void onIncoming(ChatMessage message) {}
    default void onStatus(String messageId, MsgStatus status, String detail) {}
    default void onLog(String line) {}
    default void onAnnouncement(String text) {}
    default void onLoginResult(boolean ok, String message, int udpPort) {}
}
