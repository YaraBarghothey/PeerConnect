<!-- PeerConnect - YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 -->
# Requirements Traceability Matrix — PeerConnect (assignment PeerConnect_Homework_2026.pdf)

Status legend: **Done+Tested** = implemented and verified by an executed automated test (ID from `TestCases.pdf` / `docs/test-results/`);
**Done, manual check** = implemented; needs a human / second machine to verify; **Limit** = documented limitation.
Test IDs: `T##` = integration test (real sockets), `G##` = GUI scenario check (real Swing windows on a virtual display), `M##` = manual verification.

| ID | Page § | Requirement | Implementation (files → methods) | Verification | Status / limits |
|---|---|---|---|---|---|
| R01 | p1 §3 | Persistent TCP control connection per client (LOGIN/LOGOUT, presence, discovery, notifications) | `PeerConnectServer.serve/handle`, `client/core/TcpLink` | T01, T05, T15 | Done+Tested |
| R02 | p1 §3 | UDP P2P direct chat; server MUST NOT forward | `UdpEngine.sendReliable/receiveLoop`, `MessengerClient.sendUdp`; the server owns **no DatagramSocket** | T07 (relay counter 0, no payload in server log, datagram source = sender's UDP port), G12 | Done+Tested |
| R03 | p1 §3 | TCP client-server chat: server forwards to the intended online recipient over TCP | `PeerConnectServer.relay`, `MessengerClient.sendTcp` | T08 (only addressee receives), G14/G15 | Done+Tested |
| R04 | p1 §3 | Visible transport selector (UDP P2P / TCP via Server), same pair can use both | `ClientFrame` toggle buttons `udpBtn/tcpBtn` | G11, G14, T07+T08 on same pair, screenshots | Done+Tested |
| R05 | p1 §3 | ≥5 simultaneous clients, ≥3 GUI instances | thread-per-session design | T06 (6 clients), GUI scenario (3 + 1 duplicate window) | Done+Tested |
| R06 | p1 §3 | localhost or LAN IPs | bind IP / host fields, `0.0.0.0` default | all tests on 127.0.0.1; LAN: M02 | localhost Tested; LAN manual |
| R07 | p1 §3 | Internet-wide connectivity via ZeroTier/Tailscale | server records TCP source IP as peer endpoint; README §6 instructions | M03 | **Manual** (needs two machines/networks) |
| R08 | p1 §3 | IPv4 localhost/LAN enough; no encryption, no voice/video | — | n/a | Done (nothing extra built) |
| R09 | p1 §4 | Registered users + password hashes from local file, ≥4 demo accounts | `AccountStore.load`, `config/accounts.txt` (6 accounts, PBKDF2-HMAC-SHA256, random salt, params stored) | T23 | Done+Tested |
| R10 | p1 §4 | Never store/display plaintext passwords in logs | no frame logging; `login()` logs only username/IP | T02, T23 | Done+Tested |
| R11 | p1 §4 | Validate login, reject duplicate online sessions, clear errors | `PeerConnectServer.login` (`BAD_CREDENTIALS`, `DUPLICATE_LOGIN`, `ALREADY_LOGGED_IN`), `UserDirectory.tryRegister` = atomic `putIfAbsent` | T02, T03, T04 (8 concurrent logins → exactly 1 wins), G06, G07 | Done+Tested |
| R12 | p2 §4 | Thread-safe directory: username, IP, UDP port, presence, last-seen | `UserDirectory`, `ClientSession`, `UserInfo` | T01 | Done+Tested |
| R13 | p2 §4 | Push updated user list on login, logout, status change, unexpected disconnect | `PeerConnectServer.broadcastUsers`, `cleanup` | T05, T09, T14, G04 | Done+Tested |
| R14 | p2 §4 | Relay only to addressed online user; clear delivery/error response to sender | `relay` → `RELAYED` / `OFFLINE` / `INVALID_TEXT`; response ≠ read receipt | T08, T10, T17 | Done+Tested |
| R15 | p2 §4 | Server GUI: Start/Stop, bind IP/port, active table, count, timestamped logs, broadcast | `ServerFrame` | G01–G05, G29–G34, screenshots `server_*.png` | Done+Tested |
| R16 | p2 §4 | Remove stale sessions, keep accepting after failures, clean shutdown | `sweepStale`, `acceptLoop` (catch-all), `stop()` (pool shutdown + awaitTermination) | T14, T15, T18, T25 | Done+Tested |
| R17 | p2 §5 | Three-column client GUI (identity+contacts / conversation / network+diagnostics) | `ClientFrame.leftPanel/centerPanel/rightPanel` | screenshots | Done+Tested (visual) |
| R18 | p2 §5 | Login/Logout, username, server IP/port, local UDP port, peer IP/port, TCP & UDP state, last transport, event log, clear log | `ClientFrame` fields `userField…clearLog` | G03, screenshots | Done+Tested |
| R19 | p2 §5 | Searchable online users with presence dots; unread badge; new conversation; refresh | `ContactRenderer`, `refreshContacts`, `openConversation` | G11, G13, G18 | Done+Tested |
| R20 | p2 §5 | Scrollable bubbles, sender/recipient labels, local timestamps, in/out styling, transport badges | `ClientFrame.makeBubble` | screenshots `client_udp_ack.png` etc. | Done+Tested (visual) |
| R21 | p2 §5 | Multiline compose, Send, Enter-to-send (+Shift+Enter) | `compose` InputMap/ActionMap | G27, G28 (key binding + action); real keyboard M04 | Done; keyboard manual |
| R22 | p2 §5 | Disable Send without recipient; no empty message; 1000-character limit | `updateControls`, `Protocol.validateText` (code points) | G24–G26, T17 | Done+Tested |
| R23 | p2 §5 | User-friendly errors: invalid IP/port, wrong credentials, unavailable peer, disconnected server | `Protocol.validateHost/parsePort`, `MessengerClient.friendly/errorText` | G06–G09, G19, G31, T02, T10 | Done+Tested |
| R24 | p2 §5 | Socket I/O and waits off the EDT; Swing updates via invokeLater | `ClientFrame.ui()`, `MessengerClient` executors | G10, G20, G23 (max EDT latency 74/135/98 ms while network waits 5.8 s / 2 s / 3 s) | Done+Tested |
| R25 | p2 §5 | Session history ≥50 messages per conversation in memory | `ConversationStore` (200/conversation) | T20, G16, G17 | Done+Tested |
| R26 | p2 §6 | Documented wire format + example request/response per mandatory command | ProjectReport §3, Complete_Project_Explanation_AR §5 (real captured frames) | review | Done |
| R27 | p2 §6 | TCP frames UTF-8, documented framing, user content escaped | `LineReader`, `Json.quote`, `Msg.toJson` | T16, T18 | Done+Tested |
| R28 | p2 §6 | Operations LOGIN, LOGOUT, USERS, PRESENCE, CHAT_TCP, PING, CHAT_UDP, ACK_UDP (+ PEER for discovery) | `Protocol`, `PeerConnectServer.handle`, `UdpPacket` | T01–T13 | Done+Tested |
| R29 | p2 §6 | UDP datagram has messageId, sender, recipient, timestamp, type, payload; no reliance on order | `UdpPacket` (+ version `v`) | T07, T13, T18 | Done+Tested |
| R30 | p2–3 §6 | UDP reliability: original + 2 retries, 1 s timeout, then Failed | `UdpEngine.attemptLocked/onTimeout` | T11 (3 sends, ≈3 s, gap ≈1 s), G22 | Done+Tested |
| R31 | p3 §6 | Receiver deduplicates repeated message IDs (and re-ACKs) | `DedupCache`, `UdpEngine.handleChat` | T12, T13, T24 | Done+Tested |
| R32 | p3 §6 | Message IDs unique per sending client session | `MessengerClient.Session` (random 32-bit prefix + counter) | T24 (200 ids), T21 | Done+Tested |
| R33 | p3 §6 | Show ACK only when received; do not claim guaranteed delivery | status set only in `UdpEngine.handleAck`; UI text "relayed … NOT a read receipt"; README limits | T11, T12 (all ACKs lost → Failed though delivered) | Done+Tested |
| R34 | p3 §6 | Handle malformed frames, duplicate datagrams, loss, unreachable peers, socket exceptions without crash | try/catch at every loop; strict decoders | T18, T10, T11, T13, T14 | Done+Tested |
| R35 | p3 §6 | Passwords never sent over UDP | `UdpPacket` has no password field; only TCP `LOGIN` carries it | code review + T23 | Done |
| R36 | p3 §6 | Report the security limitation of unencrypted TCP authentication | README §9, ProjectReport §8 | review | Done |
| R37 | p3 §7 | Demo scenarios: 3 clients/directory/duplicate; UDP + ACK; TCP via server; presence/offline; packet loss; unexpected termination; TCP/UDP table | `GuiScenario`, `IntegrationTests`, `docs/DemoScript_AR.md` | G01–G34, T01–T25 | Done+Tested (termination via raw socket T14; GUI process kill M05) |
| R38 | p3 §8 | ZIP with /client /server /config /screenshots /docs; source; build instructions; sample accounts; README | project tree | packaging check | Done |
| R39 | p3 §8 | ProjectReport.pdf (4–6 pages), TestCases.pdf (≥8 cases), GroupInfo.txt | `docs/ProjectReport.pdf`, `docs/TestCases.pdf`, `GroupInfo.txt` | page count check | Done (names = placeholders) |
| R40 | p3 §8 | DemoVideo.mp4 3–5 min or link | `docs/demo_automated_screen_recording.mp4` (3 min 25 s, automated, no voice) | played/probed with ffprobe | **Partial**: students must record their own narrated `DemoVideo.mp4` (M06) |
| R41 | p3 §8 | Both names + IDs in every submitted file | header comments / covers with placeholders | grep | **Placeholders** must be replaced (README §11) |
| R42 | p3 §9 | Both students can explain every part; oral discussion | `Complete_Project_Explanation_AR` (30+ Q&A) | — | Study material delivered |
| R43 | build | Java 17 + Maven with explicit versions | `pom.xml` ×5 (release 17, plugin versions pinned) | `javac --release 17` compile OK; `mvn package` **not executed** here (M01) | Compile Tested; Maven manual |
