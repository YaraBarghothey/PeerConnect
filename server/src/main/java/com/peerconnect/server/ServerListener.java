/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.server;

/** Callbacks from the server to its GUI/console. Invoked on arbitrary threads (the GUI must hop to the EDT). */
public interface ServerListener {
    void onLog(String timestampedLine);
    default void onDirectoryChanged() {}
    default void onStateChanged(boolean running) {}
}
