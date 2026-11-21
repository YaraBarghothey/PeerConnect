/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.model;
/** Message states. Each one is set only after the corresponding REAL event happened. */
public enum MsgStatus { PENDING, SENT, ACKNOWLEDGED, RECEIVED, FAILED, TIMEOUT }
