/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.model;
public enum Transport { UDP("UDP P2P"), TCP("TCP via Server"); private final String label; Transport(String l){label=l;} public String label(){return label;} public String badge(){return this==UDP?"UDP":"TCP";} }
