<!-- PeerConnect - Computer Networks I (10636454) - YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 -->
# PeerConnect — hybrid TCP/UDP desktop messenger (Java 17, Swing)

**Authors:** YOUR_NAME_1 / YOUR_ID_1 · YOUR_NAME_2 / YOUR_ID_2 *(replace the placeholders — see the last section)*

PeerConnect is a Skype-inspired text messenger with three functional parts:

| Part | Transport | What it does |
|---|---|---|
| **A. Control plane** | persistent **TCP** client↔server | LOGIN/LOGOUT, authentication, user directory + presence, peer discovery, PING heartbeat, server announcements |
| **B. P2P messaging** | **UDP**, client↔client | one-to-one chat datagrams sent **directly** to the peer's IP:port; the server never receives or forwards them. ACK + retry (original + 2 retries, 1 s) + deduplication |
| **C. Relayed messaging** | **TCP via server** | client → server → addressed client; the server answers the sender with success/error |

A visible **transport selector** (`UDP P2P` / `TCP via Server`) is in the chat window; the same two users can use either mode.
No third-party libraries are used (own small JSON codec, `java.net`, `javax.swing`, `javax.crypto`).

## 1. Requirements
* **JDK 17 or newer** (tested here with OpenJDK 21 compiling `--release 17`)
* **Apache Maven 3.8+** (normal build) — or only the JDK (fallback script, section 3)
* Windows 10/11 (BAT files). Linux/macOS: run the same `java -jar ...` commands.

## 2. Folder layout
```
client/   Swing client (module peerconnect-client)      server/  TCP server + admin GUI (module peerconnect-server)
common/   shared protocol/JSON/validation code           tests/   real-socket integration tests + GUI scenario driver
config/   server.properties, client.properties, accounts.txt (PBKDF2 hashes)
docs/     ProjectReport.pdf, TestCases.pdf, Requirements_Matrix.md, Complete_Project_Explanation_AR.{md,pdf}, DemoScript_AR.md, test-results/
screenshots/  real screenshots of the running windows (produced by the GUI scenario)
build.bat run_server.bat run_client.bat run_demo_3_clients.bat run_tests.bat      scripts/  fallback build + recording scripts
```
**Main classes:** server `com.peerconnect.server.ServerMain` · client `com.peerconnect.client.ClientMain`.

## 3. Build (Windows)
Open *Command Prompt* in the unzipped folder:
```bat
build.bat
```
This runs `mvn -q clean package` and creates `server\target\peerconnect-server.jar`, `client\target\peerconnect-client.jar`, `tests\target\peerconnect-tests.jar` (each is a runnable jar containing `common`).
**No Maven?** run `scripts\build_without_maven.bat` (needs only the JDK; produces the same three jars).

> ⚠ *Honest note:* in the environment where this project was written, Maven Central was not reachable, so `mvn package` itself
> could **not** be executed there. The sources were compiled and all tests were run with the fallback `javac`/`jar` script
> (`scripts/build_without_maven.sh`). The poms are simple (no dependencies, explicit plugin versions) but **please run `build.bat` once and
> keep the console output as evidence**. See `docs/TestCases.pdf` → "Manual Verification Required".

## 4. Run: server + three clients on one PC (localhost)
```bat
run_server.bat                 :: opens the server window, press START (bind 0.0.0.0 : 8888)
run_client.bat sara            :: window 1
run_client.bat adam            :: window 2
run_client.bat ali             :: window 3
```
or just `run_demo_3_clients.bat`. In each client enter the password and press **Login** (server IP `127.0.0.1`, port `8888`; *My UDP port* `0` = automatic, every client gets a different free port starting at 6000).

**Demo accounts** (classroom only; the file stores only salted PBKDF2 hashes): 

| user | password | user | password |
|---|---|---|---|
| sara | sara123 | saly | saly123 |
| adam | adam123 | omar | omar123 |
| ali | ali123 | aws | aws123 |

Add accounts: `java -cp server\target\peerconnect-server.jar com.peerconnect.server.AccountTool config\accounts.txt add NAME PASSWORD`.

## 5. Try the two transports
1. Log in sara, adam, ali. The contact list fills automatically (green = Online, red = Busy, amber = Away).
2. In **sara**: click *adam*, keep **UDP P2P**, type a message, press **Enter** (Shift+Enter = new line). The bubble shows `UDP`, then **✓✓ Acknowledged** when adam's ACK really arrived.
3. In **sara**: click *ali*, switch to **TCP via Server**, send. Status becomes **✓ Sent — relayed by the server (NOT a read receipt)**. The server log shows `[TCP RELAY]`; for UDP it shows nothing.
4. Change status in adam's *My status* box → everybody's list updates. *New conversation* → type `omar` (offline) → send → **Failed: user 'omar' is offline**.
5. **Retry/failure test:** in adam tick **"TEST: simulate UDP packet loss"** (TCP stays connected; adam's UDP state shows *PAUSED*). Send UDP from sara to adam: sara shows *attempt 1/3 → retransmitted 2/3 → 3/3 → Failed (no ACK)* after ≈3 s; adam shows nothing. Untick the box and send again → Acknowledged.
6. **Crash test:** close a client's console window / kill its process: the server logs `removed (client closed the connection (EOF))` and the others' lists update. Server **STOP** then **START** works repeatedly without a stuck port.

## 6. LAN and Internet (different networks)
* **Same Wi-Fi/LAN:** run the server on PC-1 (`ipconfig` → IPv4, e.g. `192.168.1.20`), allow Java through *Windows Defender Firewall* **for TCP 8888 and UDP 6000–6200** (private networks). On PC-2: `run_client.bat sara 192.168.1.20:8888`.
* **Different networks (assignment §3, NAT traversal):** use a virtual LAN so both PCs get an address that is directly reachable:
  1. **Tailscale** (easiest): install on both PCs, sign in with the same account (or share the machine), run `tailscale ip -4` (e.g. `100.101.102.103`) on the server PC.
  2. Start the server on that PC (bind `0.0.0.0` is fine) and on the other PC run `run_client.bat adam 100.101.102.103:8888`.
  3. The server records each client's **TCP source address** (its Tailscale IP) as the UDP endpoint, so UDP datagrams flow client↔client inside the tunnel. ZeroTier works the same way (join the same network ID, use the ZeroTier-assigned IPs).
  4. Verify with `ping <other tailscale ip>`; if UDP fails but TCP works, open **UDP 6000–6200** in the firewall on both PCs.
* Without such an overlay (plain NAT, no port forwarding) UDP P2P between different home networks will **not** work — TCP via Server always does.
* *Not verified here:* a real two-network test needs two physical PCs on different networks (listed as Manual Verification in TestCases.pdf).

## 7. Tests (real sockets)
```bat
run_tests.bat
```
Runs 23 integration tests (server + clients on loopback, raw-socket hostile clients). Results of the last run: `docs/test-results/integration-test-results.txt`. GUI scenario (real Swing windows under a virtual display, with screenshots): `scripts/record_demo.sh` → `docs/test-results/gui-scenario-results.txt`.

## 8. Troubleshooting
| Symptom | Fix |
|---|---|
| `Cannot bind 0.0.0.0:8888 - Address already in use` | another server is running, or choose another port in the server window |
| `Cannot connect to the server` | server not started / wrong IP-port / firewall (TCP 8888) |
| `Wrong username or password` | use the demo table above; names are case-sensitive |
| `already logged in on another client` | the account is online elsewhere; log it out first (or wait ≈15 s after a crash) |
| UDP stays *Sent … attempt 3/3* then Failed, TCP works | firewall blocks UDP on the receiver, or the *test* checkbox is ticked there |
| `UDP port N is already in use` | leave *My UDP port* = 0 |
| Window does not open / `HeadlessException` | use a normal JDK/JRE with desktop support (not a *headless* package) |
| Config not found | the program creates `config/server.properties` / `client.properties` with documented defaults; run from the project root or pass `--config DIR` |

## 9. Known limitations
* Authentication is **unencrypted TCP** (allowed by the assignment for classroom use): passwords and messages are visible on the network. No TLS/encryption is implemented.
* UDP is **not guaranteed delivery**: after 3 unacknowledged attempts the sender shows *Failed*, although the peer may still have received it (e.g. all ACKs lost). Peers are identified by username in the datagram, which can be spoofed on an untrusted LAN.
* Messages are kept in memory only (last 200 per conversation); no persistence, no offline queue.
* A client behind NAT without an overlay network cannot receive UDP from another network.
* Messages > ~1400 bytes on UDP may be IP-fragmented (works on normal LANs).
* Demo video: see `docs/DemoScript_AR.md` and `docs/demo_automated_screen_recording.mp4` (automated recording, no narration). A narrated recording by the students is a remaining step.

## 10. Reused code / sources
All code is original for this assignment; Java standard library only (JDK 17+). JSON codec, framing, PBKDF2 use, Swing layout were written from scratch. Tool versions: JDK 21 (compile target 17), Maven plugins pinned in `pom.xml` (compiler 3.11.0, jar 3.3.0, shade 3.5.1, resources 3.3.1).

## 11. Replace the placeholders
Search the project for `YOUR_NAME_1`, `YOUR_ID_1`, `YOUR_NAME_2`, `YOUR_ID_2` (e.g. `findstr /s /i "YOUR_NAME" *.*`) and replace them in: `GroupInfo.txt`, `README.md`, all `.java` headers, poms, `.bat`, `docs\*.md`, and regenerate PDFs (`docs\build_docs.py` re-creates them) or edit the cover pages. Rename the ZIP to `PeerConnect_<ID1>_<ID2>.zip`.
