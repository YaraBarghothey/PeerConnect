<!-- PeerConnect - YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 -->
# شرح مشروع PeerConnect الكامل (من الصفر) — Computer Networks I

**الطالبتان:** YOUR_NAME_1 / YOUR_ID_1 · YOUR_NAME_2 / YOUR_ID_2 *(placeholders — استبدلاها)*

> هذا الملف مكتوب لتفهمي المشروع وتناقشي أي جزء فيه. كل ما فيه مطابق للكود **النهائي** الموجود في الـZIP (أسماء الملفات والدوال حقيقية). عندما أذكر «مطلوب رسميًا» فأنا أعني أنه مذكور في ملف الأسايمنت؛ وعندما أذكر «قرار تصميم» فهو اختيارنا نحن.

## 1. فكرة المشروع ومفاهيم أساسية

PeerConnect برنامج مراسلة نصية (مثل Skype المبسّط): **سيرفر مركزي** يعرف من هو متصل، و**عملاء (Clients)** يتحاورون. المطلوب أن نستخدم **بروتوكولين مختلفين** للنقل في نفس البرنامج ونقارن بينهما.

| المفهوم | معناه في مشروعنا |
|---|---|
| **Client** | برنامج الطالب/المستخدم (نافذة Swing). يتصل بالسيرفر ويتحاور مع غيره. |
| **Server** | برنامج واحد يعمل على جهاز: يتحقق من كلمة السر، يحفظ «دليل» المتصلين، ويمرر رسائل TCP. |
| **Peer** | عميل آخر نتحدث معه مباشرة (نظير). في UDP P2P نرسل له دون المرور بالسيرفر. |
| **Socket** | «مقبس» = نقطة اتصال يوفرها نظام التشغيل. `ServerSocket`/`Socket` للـTCP و`DatagramSocket` للـUDP. |
| **IP / Port** | IP عنوان الجهاز، والـPort رقم «الباب» داخل الجهاز (1–65535). عنوان نقطة الاتصال = IP:Port. |
| **TCP** | بروتوكول **موجّه بالاتصال**: يضمن وصول البايتات كاملة وبالترتيب (يعيد الإرسال تلقائيًا). نستخدمه للتحكم وللرسائل عبر السيرفر. |
| **UDP** | بروتوكول **بلا اتصال**: يرسل «datagram» مستقلة، قد تضيع أو تتكرر أو تتبدل ترتيبها ولا يخبرك. نستخدمه للرسائل المباشرة. |
| **Control plane** | قناة «الإدارة»: LOGIN، قائمة المستخدمين، الحضور (presence)، اكتشاف النظير، PING. عندنا = اتصال TCP دائم. |
| **Data plane** | قناة «البيانات» الفعلية: نص المحادثة. عندنا = UDP مباشر بين العملاء (أو TCP عبر السيرفر كخيار). |
| **ACK** | Acknowledgment: رسالة صغيرة من المستقبل تقول «استلمت الرسالة رقم كذا». |
| **Timeout** | مدة انتظار (ثانية واحدة عندنا) نعتبر بعدها أن ACK لم يصل. |
| **Retry** | إعادة إرسال **نفس** الرسالة بنفس الـmessageId (مرتان إضافيتان، الإجمالي 3 إرساليات). |
| **Deduplication** | منع عرض الرسالة المكررة مرتين: المستقبل يتذكر `sender|messageId`. |
| **Heartbeat** | رسالة PING دورية (كل 5 ث) تثبت أن الاتصال حي؛ السيرفر يحذف من يسكت 15 ث. |
| **Presence** | حالة المستخدم: Online / Busy / Away. |
| **EDT** | Event Dispatch Thread: الخيط الوحيد المسموح له بلمس مكونات Swing. |

**لماذا الاثنان معًا؟** TCP عبر السيرفر أسهل وأضمن لكنه يمرّ بنقطة مركزية؛ UDP مباشر أسرع ولا يمرّ بالسيرفر لكنه غير موثوق، فأضفنا فوقه آلية ACK/Retry/Dedup (**مطلوب رسميًا**).

### الصورة الكبيرة (من يتكلم مع من)
```
 Client A ──TCP (LOGIN, USERS, PRESENCE, PEER, CHAT_TCP, PING)──►  Server  ◄──TCP── Client B
     │                                                                              │
     └──────────────────  UDP direct: CHAT_UDP  ⇄  ACK_UDP  ───────────────────────┘
        (IP:port الخاص بـB يأتي من السيرفر بأمر PEER، لكن الرسالة نفسها لا تمر بالسيرفر)
```

## 2. خريطة الملفات

الجذر: `PeerConnect/`. «نعدّله؟» تعني: هل تحتاجين لتعديله في الاستخدام العادي.

### 2.1 ملفات البناء والتشغيل
| الملف | الوظيفة / السبب | علاقته بغيره | نعدّله؟ |
|---|---|---|---|
| `pom.xml` (الجذر) | Parent POM: يعرّف الوحدات الأربع، Java release 17، وإصدارات الـplugins صراحة (compiler 3.11.0، jar 3.3.0، shade 3.5.1، resources 3.3.1). | يستدعي `common`,`server`,`client`,`tests` | لا |
| `common/pom.xml` | بناء مكتبة المشترك (لا dependencies). | يستخدمها الباقي | لا |
| `server/pom.xml` | يبني `peerconnect-server.jar` عبر **shade** (يدمج common داخل الـjar) ويحدد Main-Class=`ServerMain`. | يعتمد على common | لا |
| `client/pom.xml` | نفسه للعميل، Main-Class=`ClientMain`. | | لا |
| `tests/pom.xml` | jar الاختبارات (يعتمد على الثلاثة). | | لا |
| `build.bat` | يشغّل `mvn -q clean package` ويطبع OK/FAILED. | | لا |
| `run_server.bat` | يشغّل `java -jar server\target\peerconnect-server.jar %*`. | | لا |
| `run_client.bat` | `run_client.bat [user] [ip:port]` يشغّل عميلًا. | | لا |
| `run_demo_3_clients.bat` | يفتح السيرفر + sara وadam وali. | | اختياري |
| `run_tests.bat` | يشغّل اختبارات التكامل ويكتب النتيجة في `docs\test-results`. | | لا |
| `scripts/build_without_maven.sh/.bat` | بديل بناء بـ`javac`+`jar` إذا لم يتوفر Maven (هذا ما استخدمناه فعليًا في بيئة التطوير لأن Maven Central كان محجوبًا). | | لا |
| `scripts/record_demo.sh` | يشغّل `GuiScenario` على شاشة وهمية ويصوّر فيديو آليًا + لقطات. | | لا |

### 2.2 ملفات الإعداد `config/`
| الملف | المحتوى | ملاحظات |
|---|---|---|
| `server.properties` | `bindIp=0.0.0.0` (كل الواجهات)، `port=8888`، `accountsFile`، `timeoutSeconds=15` | إذا حُذف يُنشأ بقيم افتراضية موثقة |
| `client.properties` | `serverHost`, `serverPort`, `udpPortBase=6000`, `pingIntervalSeconds=5`, `serverTimeoutSeconds=15`, `defaultUsername` | يُنشأ تلقائيًا إن غاب |
| `accounts.txt` | سطر لكل حساب: `user:pbkdf2$sha256$120000$salt$hash` (6 حسابات تجريبية) | **لا** كلمات سر صريحة. للإضافة: `AccountTool` |

المسار يُحدَّد بـ`ConfigPaths.configDir()`: `-Dpeerconnect.config` ثم `./config` ثم `../config` ثم بجوار الـjar — **لا مسارات مطلقة**.

### 2.3 الكود المصدري (حزمة `com.peerconnect`)
| الملف | وظيفته المختصرة |
|---|---|
| **common/** | |
| `Json.java` | محلل/مولّد JSON خاص بنا (بدون مكتبات). يهرّب `"`, `\`, أسطر جديدة، وكل محرف تحكم. |
| `Msg.java` | غلاف بسيط لكائن JSON (get/put بأنواع). |
| `Protocol.java` | الثوابت (أسماء الأوامر وأكواد الأخطاء والحدود) + التحققات: `validateText`, `parsePort`, `validateHost`, `isValidUsername`. |
| `UdpPacket.java` | شكل datagram الـUDP: `encode()` و`decode()` الصارم. |
| `LineReader.java` | قراءة «إطار = سطر» بحد أقصى 32KiB وفحص UTF-8 صارم. |
| `PasswordHasher.java` | PBKDF2WithHmacSHA256 + salt عشوائي. |
| `DedupCache.java` | ذاكرة «ما رأيته» محدودة الحجم والعمر. |
| `Presence.java`, `UserInfo.java` | تعداد الحالات وسجل المستخدم في الدليل. |
| `ConfigPaths.java` | إيجاد مجلد config وقراءة/كتابة الخصائص. |
| **server/** | |
| **`ServerMain.java`** | **نقطة تشغيل السيرفر** (`main`). خيارات `--headless --bind --port --config`. |
| `PeerConnectServer.java` | قلب السيرفر: start/stop، حلقة القبول، معالجة الأوامر، البث. |
| `ClientSession.java` | حالة اتصال TCP واحد + صندوق صادر (outbox) وخيط كتابة وحيد. |
| `UserDirectory.java` | الدليل الآمن للخيوط (ConcurrentHashMap). |
| `AccountStore.java`, `AccountTool.java` | تحميل الحسابات/التحقق/إضافة حساب. |
| `ServerConfig.java`, `ServerListener.java` | إعدادات + واجهة إشعار الـGUI. |
| `ServerFrame.java` | نافذة إدارة السيرفر (Start/Stop، جدول، سجل، إعلان). |
| **client/** | |
| **`ClientMain.java`** | **نقطة تشغيل العميل**. خيارات `--user --server --title --config`. |
| `core/MessengerClient.java` | منطق الجلسة (بدون Swing): login/logout، إرسال TCP/UDP، أحداث. |
| `core/TcpLink.java` | اتصال TCP: خيط قراءة، منفّذ كتابة وحيد، ربط request/response. |
| `core/UdpEngine.java` | الـDatagramSocket والاستقبال وACK/Retry/Dedup. |
| `core/ClientConfig.java`, `core/MessengerListener.java` | الإعدادات + واجهة الأحداث نحو الـGUI. |
| `model/*` | `ChatMessage`, `ConversationStore`, `MsgStatus`, `Transport`, `TcpState`. |
| `ui/ClientFrame.java` | نافذة العميل ثلاثية الأعمدة. |
| **tests/** | `Harness`, `IntegrationTests` (23 اختبار sockets حقيقي)، `GuiScenario` (يقود النوافذ الحقيقية ويلتقط الصور). |

## 3. شرح الـClasses والـMethods

### 3.1 `common/Json.java`
- **المسؤولية:** تحويل نص JSON ⇄ كائنات Java. كتبناه بأنفسنا لتجنّب مكتبة خارجية (بيئة بلا Maven Central).
- `parse(String)` / `parseObject(String)`: مدخل نص، مخرج `Map<String,Object>` (أو خطأ `JsonException`). يفشل لو وُجد نص زائد بعد الكائن، أو عمق > 16، أو محرف تحكم غير مهرَّب داخل نص. رسائل الأخطاء **لا تتضمن** النص الداخل (قد يحوي كلمة سر).
- `stringify(Object)`: يكتب Map/List/String/Number/Boolean/Msg. الأهم `quote()`:
```java
case '\n': sb.append("\\n"); break;
default: if (c < 0x20 || c == '\u2028' || c == '\u2029') sb.append(String.format("\\u%04x", (int) c));
```
  لذلك لا يظهر سطر جديد خام داخل الإطار أبدًا، وهذا ما يجعل «سطر = إطار» آمنًا.
- مستدعوه: `Msg.parse/toJson`.

### 3.2 `common/Protocol.java`
حدود: `MAX_TEXT_CHARS=1000`, `MAX_FRAME_BYTES=32*1024`, `MAX_DATAGRAM_BYTES=8192`.
- `charCount(s)` = `s.codePointCount(0, s.length())` ← **عدّ محارف Unicode لا بايتات**. (1000 حرف عربي = 2000 بايت ويُقبل؛ 1001 حرف يُرفض.)
- `validateText(text)`: يعيد `null` إن صالح وإلا سبب نصي: فارغ/مسافات (`isBlank`)، أكثر من 1000، يحوي NUL، أو surrogate غير مزدوج. يُستدعى في **ثلاثة أماكن**: GUI، `MessengerClient.send`، وعند الاستقبال (السيرفر في `relay`، والعميل في حدث CHAT_TCP، و`UdpPacket.decode`).
- `parsePort(text, what, allowZero)`: يرمي `IllegalArgumentException` برسالة ودّية. `validateHost`: يقبل IPv4 صالحًا (كل جزء ≤255) أو اسم مضيف.
- `isValidUsername`: regex `[A-Za-z0-9_.-]{2,20}`؛ `isValidMessageId`: `[A-Za-z0-9_.:-]{1,80}`.

### 3.3 `common/Msg.java` و`UserInfo.java` و`Presence.java`
- `Msg.getString(k)` تعيد `null` إن المفتاح غائب أو نوعه خطأ (هكذا لا نحصل ClassCastException من إطار خبيث).
- `UserInfo` (record): `username, ip, udpPort, presence, lastSeen`. `parseList(List)` تتجاهل العناصر المشوهة ولا ترمي أبدًا.
- `Presence.parse("busy")` غير حساس لحالة الأحرف.

### 3.4 `common/LineReader.java`
- `readLine()`: يقرأ بايتات حتى `\n`. إن تجاوز 32KiB **يكمل استهلاك السطر** ثم يرمي `FrameException(FRAME_TOO_LARGE)` — فيبقى التدفق متزامنًا مع حدود الإطارات. يفك UTF-8 بـ`CodingErrorAction.REPORT` فإن كان غير صالح يرمي `BAD_ENCODING` (لا تعويض صامت). `null` = EOF نظيف؛ EOF داخل إطار = `IOException`.
- يستدعيه: `PeerConnectServer.serve` و`TcpLink.readLoop`.

### 3.5 `common/UdpPacket.java`
```java
public record UdpPacket(String type, String messageId, String sender, String recipient, long timestamp, String payload)
```
- `encode()` → bytes UTF-8 لـJSON `{"v":1,"type":...}`.
- `decode(buf, off, len)`: يفك UTF-8 صارمًا، يتحقق: `v==1`، النوع CHAT_UDP أو ACK_UDP، `messageId` صالح، `sender/recipient` أسماء صالحة، `timestamp` رقم، ونص CHAT بين 1 و1000 محرف. أي خلل ⇒ `IllegalArgumentException` (يلتقطها المستقبل ويتجاهل الحزمة).
- لا يوجد حقل كلمة سر إطلاقًا ⇒ **لا كلمات سر عبر UDP**.

### 3.6 `common/PasswordHasher.java`
- `hash(pw)` ⇒ `pbkdf2$sha256$120000$<salt b64>$<hash b64>`: salt عشوائي 16 بايت من `SecureRandom`، 120,000 دورة، مفتاح 256 بت.
- `verify(pw, stored)`: يفكك الصيغة (المعاملات مخزّنة مع الهاش)، يعيد الاشتقاق، ويقارن بـ`MessageDigest.isEqual` (زمن ثابت). أي نص تالف ⇒ `false` دون استثناء.

### 3.7 `common/DedupCache.java`
- `firstSeen(key, now)`: `true` إن المفتاح جديد. `LinkedHashMap` بترتيب الإدخال؛ `purge` يحذف المنتهي (TTL 10 دقائق) من الرأس؛ وإن زاد الحجم عن 4096 يحذف الأقدم. كل الدوال `synchronized`.

### 3.8 `server/PeerConnectServer.java` (الأهم في السيرفر)
**الحقول:** `cfg`, `accounts`, `listener`, `dir` (UserDirectory), `connections` (مجموعة كل الاتصالات بما فيها غير المسجلة), `relayedCount` (عدّاد لإثبات أن UDP لا يمر), `serverSocket`, `pool` (cached thread pool), `scheduler`, `running`.

- `start()`: ينشئ `ServerSocket`، `setReuseAddress(true)`، **`bind` متزامن** — إن فشل (BindException) يغلق ويرمي فلا تُعرض حالة Listening زورًا. ثم ينشئ pool جديدًا + scheduler لكنس الجلسات الراكدة، ويشغّل `acceptLoop` ويُخطر الـGUI بعد النجاح فقط. (كائنات جديدة في كل start ⇒ لا مهام قديمة.)
- `stop()`: `running=false`، يغلق `ServerSocket` (يوقظ accept)، يرسل `SERVER_SHUTDOWN` لكل عميل ثم `closeAfterFlush`، `scheduler.shutdownNow()`، `pool.shutdown()` + `awaitTermination(2s)` (وإلا يغلق الحسابات بالقوة)، ثم يفرغ الدليل. اختبار T15 يثبت: صفر خيوط `pc-server*` وبورت حر وإعادة تشغيل ناجحة.
- `acceptLoop(ss)`: `accept()` ثم ينشئ `ClientSession` ويشغّل خيطين (writer وreader). أي `Throwable` يُلتقط ويستمر القبول («استمرار Accept بعد أخطاء العملاء»).
- `serve(s)` (خيط القراءة): حلقة `readLine` ← `handle`. `FrameException` ⇒ يرد خطأ ويكمل. `finally { cleanup }` مضمون.
- `handle(s, line)`: يفكك JSON (فشل ⇒ `BAD_REQUEST`)، يقرأ `cmd` و`id`. `PING` و`LOGIN` مسموحان قبل الدخول؛ البقية تتطلب `s.loggedIn` وإلا `NOT_LOGGED_IN`/`UNKNOWN_COMMAND`. يوزع على `login`, `relay`, أو معالجات USERS/PRESENCE/PEER/LOGOUT.
- `login(s, id, req)`: (1) إن `s.loggedIn` ⇒ `ALREADY_LOGGED_IN` دون المساس بأي جلسة؛ (2) تحقق الحقول والمنفذ 1–65535؛ (3) `accounts.verify` ⇒ `BAD_CREDENTIALS`؛ (4) يضبط username/udpPort على الجلسة **قبل** التسجيل؛ (5) **`dir.tryRegister(s)`** = `putIfAbsent` ذري؛ إن فشل ⇒ `DUPLICATE_LOGIN`؛ (6) `loggedIn=true`، يرد بقائمة المستخدمين، ثم `broadcastUsers()`.
- `relay(...)`: يتحقق المستلم والنص والـmessageId، يرفض الإرسال للنفس، يبحث في الدليل: غائب ⇒ `OFFLINE`. يبني إطار EVENT `CHAT_TCP` ويضعه في outbox **المستلم فقط**؛ نجاح ⇒ يرد للمرسل `status:"RELAYED"` (وهذا لا يعني أن المستلم قرأ).
- `cleanup(s, reason)`: `wasRegistered = s.loggedIn && dir.remove(s)` — الدالة `remove(user, session)` تحذف **فقط** إن كان اسم المستخدم ما زال مربوطًا بهذه الجلسة بالذات (لا تحذف جلسة أحدث). ثم `closeAfterFlush` وإزالة من `connections`، وإن كان مسجلًا: log + `broadcastUsers`.
- `sweepStale()` (كل ≤1 ث): يغلق أي اتصال `now - lastSeen > timeoutMs` (غير مسجل أيضًا). `lastSeen` يُحدَّث عند أي إطار.
- `deliver(s, frame)`: `enqueue` — إن امتلأ الـoutbox (عميل بطيء) يُغلق ذلك العميل بدل أن يحجب الباقين.
- `broadcastUsers()`: يبني JSON واحدًا للدليل ويرسله لكل الجلسات المسجلة. `broadcastAnnouncement(text)` للإعلانات.

### 3.9 `server/ClientSession.java`
- حقول: `socket`, `reader`, `ip` (عنوان TCP المصدر — يُعلَن كـIP للـUDP), `outbox` (LinkedBlockingQueue بسعة 512), `closed` (AtomicBoolean)، و`volatile`: `username, udpPort, presence, loggedIn, lastSeen`.
- `enqueue(line)`: `false` إن مغلق/ممتلئ. `writerLoop()`: **الخيط الوحيد الذي يكتب** على المقبس: `take()` ثم `write(bytes + '\n')` ثم `flush`؛ العلامة `CLOSE_MARKER` تنهي الحلقة بعد تفريغ ما قبلها (`closeAfterFlush`). `close()` فوري ومتكرر الأمان.
- لماذا هكذا؟ لا تداخل إطارات (كاتب واحد)، ولا يعلق بث السيرفر على عميل بطيء.

### 3.10 `server/UserDirectory.java`
`tryRegister` = `putIfAbsent(...)==null`؛ `remove(s)` = `map.remove(username, s)`؛ `snapshot()` قائمة مرتبة `UserInfo`. **لماذا لا تكفي `ConcurrentHashMap` وحدها؟** لأن `containsKey` ثم `put` عمليتان منفصلتان: خيطان قد يمرّان الفحص معًا ويضعان معًا (race). الحل أن تكون «الفحص+الوضع» عملية ذرية واحدة (`putIfAbsent`) — T04 يثبت: 8 محاولات متزامنة ⇒ نجاح واحد فقط.

### 3.11 `server/AccountStore.java`
`load(file)` يقرأ `user:hash`؛ `verify(user, pw)`: للمستخدم المجهول يحسب PBKDF2 على هاش وهمي كي لا يتسرب وجود الحساب بالزمن؛ `ensureDemoAccounts` ينشئ الملف إن غاب؛ `addAccount` يضيف/يبدل حسابًا.

### 3.12 `server/ServerFrame.java`
واجهة السيرفر. `doStart()` يتحقق من المنفذ (1–65535) ثم يشغّل البدء على `control` executor (خيط خلفي): `new PeerConnectServer(...).start()`؛ عند الفشل يعرض الخطأ ويعيد الحالة STOPPED؛ حالة `LISTENING` تُضبط فقط داخل `onStateChanged(true)` بعد نجاح bind. `doStop()` خلفي أيضًا. `refreshTable()` من الـEDT (Timer كل ثانية + عند تغيّر الدليل). `onLog` يضيف للسجل عبر `invokeLater`. إغلاق النافذة ⇒ stop في الخلفية ثم `System.exit`.

### 3.13 `client/core/TcpLink.java`
- `connect()` (حاجب: DNS+اتصال، لا يُستدعى على EDT): `Socket.connect(addr, 5000)`، `setSoTimeout(serverTimeoutMs)` ⇒ سكوت السيرفر 15 ث = انقطاع. يبدأ `pc-client-tcp-reader`.
- `request(Msg)`: يعطي `id` فريدًا (`r1`,`r2`…) ويخزّن `CompletableFuture` في `pending`، ويضع مهمة الكتابة في **منفّذ بخيط واحد** `writer`. فشل الكتابة ⇒ `completeExceptionally` (فلا يظهر «Sent» كذبًا) + إغلاق الاتصال.
- `readLoop()`: `RESPONSE` ⇒ `pending.remove(id).complete(m)` (ربط الرد بالطلب)؛ `EVENT` ⇒ `handler.onEvent`. EOF/IOException/Timeout ⇒ `shutdown(reason, true)` ⇒ يفشل كل الطلبات المعلقة ويستدعي `onClosed`. بهذا **EOF يعطّل الإرسال حتى دون IOException**.

### 3.14 `client/core/UdpEngine.java`
- `open(self, wantedPort, autoBase, cb)`: إن طُلب منفذ محدد يربط به أو يرمي «UDP port N is already in use»؛ وإن 0 يجرب 6000..6199 بالتتابع (`BindException` ⇒ التالي) ثم أي منفذ حر. **هنا يُنشأ `DatagramSocket`** (`bind()` الخاصة).
- `start()`: خيط `pc-client-udp-recv` + مهمة تنظيف الـdedup كل دقيقة.
- `sendReliable(id, peer, ip, port, data)`: يسجّل `Pending` ثم `attemptLocked`:
```java
p.attempts++;
socket.send(new DatagramPacket(p.data, p.data.length, p.ip, p.port));   // هنا تخرج رسالة UDP
cb.onStatus(p.id, MsgStatus.SENT, "Sent via UDP, waiting for ACK (attempt n/3)");
p.timer = sched.schedule(() -> onTimeout(p), 1000, MILLISECONDS);
```
- `onTimeout(p)`: داخل `synchronized(p)`: إن `done` يخرج؛ إن `attempts<3` يعيد الإرسال؛ وإلا `finishLocked(FAILED)`.
- `handleAck`: يبحث `pending.get(messageId)`؛ يتحقق أن `ack.sender == p.peer` (ACK يخص **هذه** الرسالة و**هذا** الطرف)؛ `synchronized(p){ if(!done) finishLocked(ACKNOWLEDGED) }` ويلغي المؤقت.
- **منع السباق:** لكل رسالة معلّقة monitor وعلم `done`؛ ACK والمؤقت وإلغاء Logout كلهم يأخذون الـmonitor ويفحصون `done` ⇒ واحد فقط يفوز.
- `receiveLoop/handle`: يتجاهل (ولا يتوقف) أمام: حزمة مشوهة، حجم ≥ الـbuffer، مستلم غير أنا. وضع الاختبار: إن `!receiveEnabled` تُسقط الحزمة **قبل فك التشفير** (لا ACK ولا عرض) وتُعدّ في `droppedWhilePaused`.
- `handleChat`: `first = dedup.firstSeen(sender|id)`؛ **دائمًا** `sendAck` (حتى للمكرر لأن ACK الأول قد يكون ضاع)؛ `if(first) cb.onIncoming` وإلا يسجل «Duplicate … NOT displayed again».
- `sendAck`: يرسل `ACK_UDP` إلى عنوان **مصدر** الحزمة (نفس المقبس). خطاف الاختبار `dropNextAcks(n)` يتخطى n ACK.
- `close(reason)`: يغلق المقبس (يوقظ receive)، `sched.shutdownNow()`، يلغي كل Pending بحالة FAILED «Cancelled: …» (فلا إعادة إرسال بعد Logout).

### 3.15 `client/core/MessengerClient.java`
- `Session` (فئة داخلية): `id` (بادئة عشوائية 32 بت مثل `s1a2b3c4d`)، `counter`، `tcp`, `udp`, `heartbeat`, `work` (4 خيوط)، `closed`. رقم الرسالة: `s1a2b3c4d-7` ⇒ فريد لكل جلسة.
- `login(host, portText, user, pass, udpText)`: تحقق متزامن سريع (يرمي IAE برسالة ودّية: IP/port/username/password) ثم `control.execute(doLogin)`.
- `doLogin` — **الترتيب الصحيح:** (1) `UdpEngine.open` أولًا لمعرفة المنفذ الحقيقي؛ (2) `TcpLink.connect`؛ (3) `LOGIN` مع `udpPort` الحقيقي؛ (4) عند النجاح فقط: `session = s`، `udp.start()` (مستمع واحد لكل جلسة)، بدء heartbeat، وإخطار الـGUI. أي فشل ⇒ `teardown` + رسالة ودّية عبر `friendly(Throwable)` (ConnectException ⇒ «Cannot connect… », BAD_CREDENTIALS ⇒ «Wrong username or password»…).
- `teardown(s, reason)`: **idempotent** (`closed.compareAndSet`): يوقف heartbeat، يغلق UDP (يلغي المعلّق) وTCP، ويوقف الـexecutor، ويفرغ `session`. يُستدعى من logout/فقد الاتصال/الإغلاق.
- `send(peer, text, transport)`: متزامن وسريع: يتحقق (غير متصل ⇒ ISE؛ مستلم غير صالح/نفسي/نص ⇒ IAE)، ينشئ `ChatMessage` بحالة PENDING ويرسل المهمة إلى `work`. يعيد الرسالة فورًا.
- `sendTcp`: طلب `CHAT_TCP` ثم `.get(5s)`: ok ⇒ SENT («Relayed by the server… NOT a read receipt»)، خطأ ⇒ FAILED (OFFLINE ⇒ «user is offline»)، `TimeoutException` ⇒ TIMEOUT.
- `sendUdp`: طلب `PEER` (اكتشاف النظير **الحالي** — لا نعيد استخدام endpoint قديم)، ثم `udp.sendReliable(...)`. المستخدم غير المتصل ⇒ FAILED دون إرسال أي datagram.
- `tcpHandler.onEvent`: `USERS_UPDATE` ⇒ `onUsers`; `CHAT_TCP` ⇒ **تحقق ثانٍ** (مرسل، id، نص ≤1000) ثم `onIncoming`; `ANNOUNCEMENT`. `connectionLost` ⇒ teardown + إخطار DISCONNECTED.
- `setUdpReceiveEnabled(false)`: وضع محاكاة ضياع UDP (TCP يبقى متصلًا).

### 3.16 `client/ui/ClientFrame.java`
- `ui(Runnable)`: كل ردود `MessengerListener` تأتي من خيوط الشبكة وتُمرَّر عبر `SwingUtilities.invokeLater`.
- `doLogin()` يستدعي `client.login` (يعود فورًا)، `doSend()` يقرأ النص (`strip`)، `Protocol.validateText`، ثم `client.send` ويضيف الفقاعة بحالة Pending.
- `onStatus(id, status, detail)`: يحدّث كائن `ChatMessage` ثم ملصق الحالة في الفقاعة — **الحالة لا تتغير إلا عند وقوع الحدث الفعلي**.
- `refreshContacts()`: يبني القائمة من (المتصلين ∪ من لهم محادثة ∪ محادثات يدوية)، ويعيد تحديد المستخدم المحدّد **بالاسم** لا بالفهرس (تزامن تحديثات الدليل مع الاختيار). `updateControls()` تعطّل Send عند: غير متصل، لا مستلم، نص فارغ، أو >1000.
- Enter/Shift+Enter: `InputMap` يربط Enter بإجراء `send-message` وShift+Enter بالإجراء الافتراضي `insert-break`.
- `makeBubble(m)`: فقاعة بلون مختلف للصادر/الوارد، رأس `You → adam | 13:45:09` وشارة UDP/TCP ملونة، النص RTL تلقائيًا إن بدأ بحرف عربي.
- `closeApp()`: يجري `client.shutdown` في خيط خلفي ثم `System.exit`.

## 4. تتبّع سيناريوهات التنفيذ (ملفات ← دوال ← Threads ← بروتوكول)

**الرموز:** [EDT] خيط Swing، [ctl] `pc-client-control`، [work] `pc-client-work-N`، [rd] `pc-client-tcp-reader`، [wr] `pc-client-tcp-writer`، [udp] `pc-client-udp-recv`، [retry] `pc-client-udp-retry`، [S-acc] خيط accept في السيرفر، [S-rd]/[S-wr] قراءة/كتابة جلسة في السيرفر.

### 4.1 تشغيل السيرفر
`ServerMain.main` → `ConfigPaths.configDir()` → `ServerConfig.load` (ينشئ الافتراضي إن غاب) → `AccountStore.ensureDemoAccounts/load` → [EDT] `new ServerFrame(...)`. الضغط على START: `ServerFrame.doStart` (تحقق منفذ على EDT) → executor خلفي `PeerConnectServer.start()` → `bind` → `scheduler` و`acceptLoop` → `onStateChanged(true)` → [EDT] `setRunning()` فتظهر `LISTENING`. إن كان المنفذ مشغولًا: `BindException` ⇒ رسالة حمراء والحالة STOPPED.

### 4.2 تشغيل العميل والدخول
`ClientMain.main` (أو `run_client.bat sara`) → `ClientConfig.load` → [EDT] `new ClientFrame`. المستخدم يكتب كلمة السر ويضغط Login: [EDT] `doLogin` → `MessengerClient.login` (تحقق متزامن) → [ctl] `doLogin`: `UdpEngine.open` (ربط UDP أولًا) → `TcpLink.connect` (يبدأ [rd]) → `request(LOGIN)` [wr] → السيرفر [S-rd] `handle→login` → رد `RESPONSE ok` + `USERS_UPDATE` للجميع → [rd] يكمل الـfuture → [ctl] `session=s; udp.start()` (يبدأ [udp]) + heartbeat → `listener.onLoginResult` → [EDT] يحدّث الواجهة.

### 4.3 دخول خاطئ / مكرر
- **كلمة سر خاطئة:** `accounts.verify` false ⇒ `BAD_CREDENTIALS` ⇒ العميل: `LoginRejected` ⇒ `friendly` ⇒ «Wrong username or password»؛ `teardown` يغلق UDP وTCP (لا تسريب).
- **مكرر:** `tryRegister` فشل ⇒ `DUPLICATE_LOGIN`؛ الجلسة الأولى لم تُمسّ. عند إغلاق اتصال الخاسر: `cleanup` يرى `loggedIn=false` فلا يحذف شيئًا.

### 4.4 تحديث دليل المستخدمين و5. تغيير الحالة
أي login/logout/presence/انقطاع ⇒ السيرفر `broadcastUsers()` ⇒ لكل جلسة outbox ⇒ [S-wr] يكتب `{"type":"EVENT","event":"USERS_UPDATE","users":[...]}` ⇒ [rd] `tcpHandler.onEvent` ⇒ `onUsers` ⇒ [EDT] `refreshContacts`. تغيير الحالة: Combo في `ClientFrame` ⇒ `setPresence` ⇒ `PRESENCE` ⇒ `s.presence=…` ⇒ بث.

### 4.6 اكتشاف النظير (Peer discovery)
قبل كل إرسال UDP: [work] `sendUdp` ⇒ `PEER {"username":"adam"}` ⇒ السيرفر يرد `peer:{ip,udpPort,…}` من الدليل أو `OFFLINE`. (IP = عنوان مصدر اتصال TCP؛ المنفذ = ما أعلنه العميل في LOGIN.)

### 4.7 رسالة TCP من البداية للنهاية
[EDT] Send ⇒ `client.send(.., TCP)` ⇒ [work] `sendTcp` ⇒ `request(CHAT_TCP)` ⇒ [wr] يكتب ⇒ [S-rd] `relay` ⇒ `deliver(target)` (outbox المستلم) ⇒ [S-wr المستلم] يكتب EVENT ⇒ [rd المستلم] `onEvent` ⇒ تحقق ثانٍ ⇒ `onIncoming` ⇒ [EDT] فقاعة + شارة unread. وفي الاتجاه المعاكس: السيرفر يرد للمرسل `RELAYED` ⇒ `onStatus(SENT)`.

### 4.8 رسالة UDP من البداية للنهاية
[EDT] Send ⇒ [work] `sendUdp` ⇒ `PEER` ⇒ `UdpPacket(CHAT).encode()` ⇒ `udp.sendReliable` ⇒ `socket.send(...)` (**خروج الرسالة، لا يمر بالسيرفر**) ⇒ المستقبل [udp] `receiveLoop` ⇒ `handle` ⇒ `decode` ⇒ `handleChat` ⇒ `sendAck` + `onIncoming` ⇒ [EDT]. المرسل [udp] يستقبل `ACK_UDP` ⇒ `handleAck` ⇒ `onStatus(ACKNOWLEDGED)`.

### 4.9 استلام ACK
`handleAck`: `pending.get(id)` ⇒ تحقق `sender==peer` ⇒ `synchronized(p){finishLocked}` ⇒ يلغي المؤقت ⇒ الحالة «✓✓ Acknowledged».

### 4.10 ضياع الرسالة (Packet loss)
المستقبل في وضع الاختبار يسقط الحزم. المرسل: t=0 إرسال 1، t=1 s إرسال 2 «retransmitted (attempt 2/3)»، t=2 s إرسال 3، t=3 s `FAILED: no ACK after 3 attempts`. (T11: 3 إرساليات، فاصل ≈1 ث، المستقبل أسقط 3 ولم يعرض شيئًا، TCP بقي متصلًا.)

### 4.11 ضياع ACK
الرسالة وصلت وعُرضت مرة؛ ACK ضاع. المرسل يعيد الإرسال بنفس `messageId`؛ المستقبل `firstSeen=false` ⇒ يرسل ACK ثانية **دون عرض**. (T12: يسقط ACK مرتين ⇒ النجاح عند الثالثة وعُرضت مرة واحدة.) إن ضاعت الثلاثة يظهر Failed رغم أن المستقبل عرضها — لذلك لا ندّعي ضمان التسليم.

### 4.12 Datagram مكرر
المكرر (شبكة/إعادة) ⇒ نفس المسار: ACK بلا عرض (T13: 3 نسخ ⇒ 3 ACK وعرض واحد؛ نفس الـid من مرسل آخر رسالة جديدة لأن المفتاح `sender|id`).

### 4.13 مستخدم غير متصل
TCP: السيرفر `OFFLINE` ⇒ «Failed: user 'omar' is offline». UDP: `PEER` يرجع OFFLINE ⇒ نفس الرسالة ولا يُرسل أي datagram. الواجهة لا تتجمد لأن كل شيء في [work] (T10، وG20: أقصى تأخر EDT=135ms).

### 4.14 انقطاع السيرفر
[rd] يرى EOF/Timeout/خطأ ⇒ `shutdown(reason,true)` ⇒ `connectionLost` ⇒ `teardown` + `onTcpState(DISCONNECTED)` ⇒ [EDT] تعطيل Send وإظهار السبب. السيرفر عند Stop يرسل `SERVER_SHUTDOWN` أولًا.

### 4.15 إغلاق عميل بشكل مفاجئ
إغلاق/قتل العملية ⇒ RST/EOF ⇒ [S-rd] `readLine` يرمي/يرجع null ⇒ `finally cleanup` ⇒ `dir.remove(s)` ⇒ log «removed» ⇒ `broadcastUsers`. إن كان الجهاز صامتًا بلا إغلاق: `sweepStale` يغلقه بعد `timeoutMs` (15 ث). (T14.)

### 4.16 Logout والتنظيف
`logout()` [ctl]: `LOGOUT` (≤1.5 ث) ⇒ `teardown`: heartbeat/UDP(يلغي المعلّق «Cancelled: logged out»)/TCP/executors ⇒ `onTcpState(DISCONNECTED)`. السيرفر: `handle` يرد OK ويرجع false ⇒ `cleanup` ⇒ بث. Login جديد = `Session` جديدة (مقبس جديد، بادئة id جديدة) ولا يُعاد إرسال شيء قديم (T21).

## 5. البروتوكول (الصيغة النهائية الفعلية)

### 5.1 الإطارات (TCP)
- **Framing:** JSON Lines: كائن JSON في سطر، UTF-8، ينتهي بـ`\n`. الحد الأقصى 32KiB.
- **Escaping:** `Json.quote` يهرّب `"` و`\` والأسطر وكل محرف تحكم؛ النص العربي والإيموجي يُكتبان كما هما UTF-8 (ما عدا U+2028/2029). لذا لا يمكن لرسالة المستخدم أن «تكسر» الإطار أو تحقن حقولًا.
- **UTF-8 صريح:** `getBytes(StandardCharsets.UTF_8)` عند الكتابة و`CharsetDecoder` صارم عند القراءة؛ لا `split` عشوائي على المحتوى.
- **ربط الرد بالطلب:** الطلب فيه `id`؛ الرد `{"type":"RESPONSE","id":<نفسه>,...}`. أما `EVENT` فلا `id` له (غير متزامن).
- **منع اختلاط الإطارات:** عميل: كاتب وحيد (`TcpLink.writer`)؛ سيرفر: كاتب وحيد لكل جلسة (outbox).

### 5.2 جدول الأوامر
| الأمر | الحقول (طلب) | الرد |
|---|---|---|
| `LOGIN` | `id, cmd, username, password, udpPort` | `ok, username, ip, udpPort, users[]` أو `error` ∈ {BAD_CREDENTIALS, DUPLICATE_LOGIN, ALREADY_LOGGED_IN, BAD_REQUEST} |
| `LOGOUT` | `id, cmd` | `ok` |
| `USERS` | `id, cmd` | `ok, users[]` |
| `PRESENCE` | `id, cmd, status` (Online/Busy/Away) | `ok, status` + بث `USERS_UPDATE` |
| `PEER` (إضافة منا لاكتشاف النظير) | `id, cmd, username` | `ok, peer{…}` أو `OFFLINE` |
| `CHAT_TCP` | `id, cmd, to, text, messageId?` | `ok, status:"RELAYED", messageId` أو `OFFLINE/INVALID_TEXT/BAD_REQUEST/DELIVERY_FAILED` |
| `PING` | `id, cmd` | `ok, pong:true, serverTime` |
| أحداث | — | `USERS_UPDATE{users}`, `CHAT_TCP{messageId,from,to,text,timestamp}`, `ANNOUNCEMENT{text,timestamp}`, `SERVER_SHUTDOWN{text}` |

### 5.3 أمثلة فعلية (التقطت من سيرفر يعمل)
```
C->S {"id":"r1","cmd":"LOGIN","username":"sara","password":"<كلمة السر>","udpPort":6000}
S->C {"type":"RESPONSE","id":"r1","cmd":"LOGIN","ok":true,"username":"sara","ip":"127.0.0.1","udpPort":6000,"users":[{"username":"sara","ip":"127.0.0.1","udpPort":6000,"presence":"Online","lastSeen":1794994375500}]}
S->C {"type":"EVENT","event":"USERS_UPDATE","users":[{"username":"adam",...},{"username":"sara",...}]}
C->S {"id":"r3","cmd":"PRESENCE","status":"Busy"}
S->C {"type":"RESPONSE","id":"r3","cmd":"PRESENCE","ok":true,"status":"Busy"}
C->S {"id":"r5","cmd":"CHAT_TCP","to":"adam","messageId":"s1a2b3c4d-7","text":"مرحبا\nسطر ثان \"اقتباس\""}
S->C(sara) {"type":"RESPONSE","id":"r5","cmd":"CHAT_TCP","ok":true,"status":"RELAYED","messageId":"s1a2b3c4d-7"}
S->C(adam) {"type":"EVENT","event":"CHAT_TCP","messageId":"s1a2b3c4d-7","from":"sara","to":"adam","text":"مرحبا\nسطر ثان \"اقتباس\"","timestamp":1794994375788}
C->S {"id":"r6","cmd":"CHAT_TCP","to":"omar","text":"hi"}
S->C {"type":"RESPONSE","id":"r6","cmd":"CHAT_TCP","ok":false,"error":"OFFLINE","message":"User 'omar' is offline"}
S->C {"type":"RESPONSE","id":"y1","cmd":"LOGIN","ok":false,"error":"BAD_CREDENTIALS","message":"Wrong username or password"}
C->S not json
S->C {"type":"RESPONSE","id":null,"cmd":null,"ok":false,"error":"BAD_REQUEST","message":"Malformed JSON frame"}
```
(في الملف الحقيقي تظهر كلمة السر الفعلية في طلب LOGIN فقط؛ ولا تُسجَّل في أي log.)

### 5.4 UDP
```
CHAT_UDP: {"v":1,"type":"CHAT_UDP","messageId":"s1a2b3c4d-7","sender":"sara","recipient":"adam","timestamp":1794994375788,"payload":"Hi Adam"}
ACK_UDP : {"v":1,"type":"ACK_UDP","messageId":"s1a2b3c4d-7","sender":"adam","recipient":"sara","timestamp":1794994375790,"payload":""}
```
| الحقل | معناه |
|---|---|
| `v` | رقم نسخة البروتوكول (1) |
| `type` | `CHAT_UDP` أو `ACK_UDP` |
| `messageId` | فريد لكل جلسة عميل؛ ACK يحمل نفس الرقم |
| `sender` / `recipient` | أسماء المستخدمين؛ المستقبل يتحقق أنه المقصود |
| `timestamp` | وقت الإرسال (ms) |
| `payload` | النص (1–1000 محرف) أو فارغ في ACK |

حجم buffer الاستقبال 8192 بايت؛ أسوأ حالة: 1000 محرف تحكم × 6 بايت (`\u0001`) ≈ 6000 + غلاف ≈ 6200 < 8192 (اختبرنا ذلك في T17). **لا اعتماد على الترتيب**: كل datagram مستقلة وACK يُربط بالـid.

## 6. التزامن (Concurrency)
**ما هو EDT؟** الخيط الوحيد الذي يرسم ويعدّل مكونات Swing. لو حجزناه بقراءة مقبس أو `Future.get()` تتجمد النافذة؛ ولو عدّلنا الواجهة من خيط آخر قد تتلف.

| الخيط | يعمل عليه |
|---|---|
| **EDT** | بناء/تحديث الواجهة فقط؛ أحداث الأزرار تستدعي دوال تعود فورًا |
| `pc-client-control` | login/logout |
| `pc-client-work-N` | `PEER`، إرسال TCP/UDP، انتظار الرد (`get(5s)` خارج EDT) |
| `pc-client-tcp-reader/-writer` | قراءة/كتابة المقبس |
| `pc-client-udp-recv` / `-retry` | استقبال UDP / مؤقتات إعادة الإرسال |
| `pc-client-heartbeat` | PING كل 5 ث |
| السيرفر: accept + reader + writer لكل عميل + scheduler | |

**حماية الحالة المشتركة:**
- الدليل: `ConcurrentHashMap` + عمليات ذرية (`putIfAbsent`, `remove(k,v)`).
- سجل الرسائل المعلّقة: `ConcurrentHashMap` + monitor لكل رسالة + علم `done`.
- الكتابة: خيط/منفّذ وحيد لكل اتصال.
- الأعلام والعدادات: `volatile` / `Atomic*`. `teardown` بـ`compareAndSet`.
- الاستدعاءات المعلّقة (futures) تُفشَل جميعًا عند إغلاق الاتصال.

**سباق ACK/Retry/Timeout:** لو وصل ACK مع انتهاء المؤقت: الأول الذي يدخل `synchronized(p)` يضع `done=true`؛ الثاني يرى `done` ويعود. لا يمكن أن تظهر «Acknowledged» ثم «Failed» لنفس الرسالة.

**لماذا لا تكفي `ConcurrentHashMap` لـcheck-then-put؟** كل دالة فيها آمنة بذاتها، لكن التسلسل `if(!map.containsKey(u)) map.put(u,s)` ليس ذريًا: خيطان يريان «غير موجود» ثم يضعان كلاهما ⇒ جلستان لنفس المستخدم. `putIfAbsent` تفعل الفحص والوضع كوحدة واحدة.

## 7. خطوات التشغيل على Windows (من فك الـZIP)
1. فكّي `PeerConnect_ID1_ID2.zip` في مجلد بلا مسافات/عربي إن أمكن (مثلًا `C:\PeerConnect`).
2. تأكدي: `java -version` (17+) و`mvn -version`. (بدون Maven: `scripts\build_without_maven.bat`.)
3. `build.bat` ← يجب أن تظهر `BUILD OK` وتُنشأ jars في `server\target`, `client\target`, `tests\target`.
4. `run_server.bat` ← نافذة السيرفر: Bind IP `0.0.0.0`، Port `8888` ← **START** ← `LISTENING`.
5. ثلاث نوافذ CMD: `run_client.bat sara` و`adam` و`ali` (أو `run_demo_3_clients.bat`). كلمات السر: `sara123`, `adam123`, `ali123`, `aws123`, `saly123`, `omar123`. Server IP=`127.0.0.1` Port=`8888`، My UDP port=`0`.
6. جهازان على LAN: على جهاز السيرفر `ipconfig` ⇒ IPv4 (مثل 192.168.1.20)؛ على الآخر `run_client.bat sara 192.168.1.20:8888`؛ افتحي Firewall لـTCP 8888 وUDP 6000–6200.
7. شبكتان مختلفتان: ثبّتي **Tailscale** على الجهازين بنفس الحساب، خذي IP السيرفر من `tailscale ip -4` (100.x.y.z) واستخدميه بدل عنوان LAN. (غير مختبر عمليًا هنا.)

**أخطاء شائعة:** `Address already in use` (سيرفر آخر على 8888)؛ `Cannot connect` (السيرفر لم يبدأ/IP أو Firewall)؛ `already logged in` (الحساب متصل في مكان آخر)؛ UDP يفشل بينما TCP يعمل (Firewall يحجب UDP أو خانة الاختبار مفعّلة)؛ `HeadlessException` (JRE بلا واجهة).

## 8. التحضير للمناقشة — أسئلة متوقعة وإجاباتها

**1) أين `DatagramSocket`؟** في `client/core/UdpEngine.java`: الدالة `bind(port)` تنشئ `new DatagramSocket(null)` ثم `bind(new InetSocketAddress(port))`؛ تُستدعى من `UdpEngine.open` التي يستدعيها `MessengerClient.doLogin`. **السيرفر لا يملك `DatagramSocket` إطلاقًا** (ابحثي عنها في `server/` فلن تجديها).

**2) أين تخرج رسالة UDP؟** `UdpEngine.attemptLocked`: السطر `socket.send(new DatagramPacket(p.data, p.data.length, p.ip, p.port))`. الوجهة = IP:port النظير من ردّ `PEER`.

**3) كيف نثبت أنها لا تمر بالسيرفر؟** (أ) الكود: لا مقبس UDP في السيرفر؛ (ب) السجل: عند UDP لا يظهر `[TCP RELAY]` وعند TCP يظهر؛ (ج) `getRelayedCount()==0` بعد رسالة UDP (T07)؛ (د) المستقبل يسجّل عنوان المصدر `127.0.0.1:<منفذ UDP للمرسل>` (T07)؛ (هـ) لقطة `server_tcp_relay_log.png` لا تحوي أثرًا لرسائل UDP؛ (و) فكرة إضافية للعرض: Wireshark على loopback بفلتر `udp.port==6000`.

**4) ماذا لو ضاعت الرسالة؟** لا ACK ⇒ بعد ثانية إعادة إرسال بنفس الـid، ثم مرة أخرى؛ بعد الثالثة وانتهاء ثانيتها (≈3 ث) تظهر Failed. الكود: `UdpEngine.onTimeout`.

**5) ماذا لو ضاع ACK؟** الرسالة وصلت لكن المرسل لا يعرف ⇒ يعيد الإرسال ⇒ المستقبل يرى id مكررًا فيعيد ACK ولا يعرض. إن ضاعت كل الـACK يظهر Failed رغم وصولها (حد معروف للبروتوكولات غير المضمونة).

**6) لماذا لا نكرر العرض؟** `DedupCache.firstSeen(sender|messageId)` في `UdpEngine.handleChat`؛ التكرار يحدث بإعادة الإرسال أو بتكرار الشبكة.

**7) كيف يُكتشف disconnect؟** ثلاث طرق: EOF/استثناء في القراءة (فوري)، heartbeat: العميل يرسل PING كل 5 ث والسيرفر يحذف الصامت بعد 15 ث (`sweepStale`)، وعلى العميل `setSoTimeout(15s)` لاكتشاف صمت السيرفر. (T14، T15.)

**8) الفرق بين موثوقية TCP وACK التطبيقي؟** TCP يضمن وصول البايتات إلى **نظام تشغيل** الطرف الآخر (السيرفر)، لا أن المستخدم المقصود استلم/قرأ. لذلك حالة TCP عندنا «Relayed by the server (NOT a read receipt)». أما ACK_UDP فهو تأكيد من **تطبيق المستقبل** نفسه أنه استلم الرسالة.

**9) كيف تمنعين تجميد الـGUI؟** لا I/O على EDT: `MessengerClient` يعود فورًا وينفذ الشبكة في خيوط `control/work/reader/udp`، والنتائج تعود بـ`invokeLater`. أثبتنا بالقياس (G10/G20/G23): أقصى تأخر EDT 74/135/98 ms أثناء انتظار شبكي 5.8/2/3 ثوانٍ.

**10) كيف تُخزَّن كلمات المرور؟** PBKDF2-HMAC-SHA256، 120k دورة، salt عشوائي 16 بايت لكل حساب، المعاملات تُخزَّن مع الهاش؛ لا plaintext في الملف ولا في logs (T23). التحقق بمقارنة زمن ثابت.

**11) كيف تشتغلون من شبكتين مختلفتين؟** NAT يمنع الاتصال الداخل المباشر، فنستخدم شبكة افتراضية (Tailscale/ZeroTier) تعطي كل جهاز IP قابلًا للوصول. السيرفر يسجل عنوان TCP المصدر (IP الـTailscale) كـendpoint للـUDP، فيتدفق UDP داخل النفق. **لم نختبرها عمليًا** (تحتاج جهازين وشبكتين) وهي مدرجة Manual.

**12) ماذا يحدث عند Stop ثم Start؟** `stop()` يغلق ServerSocket، يرسل SERVER_SHUTDOWN، يغلق الجلسات، `awaitTermination`، يفرغ الدليل. `start()` ينشئ `ServerSocket`+pool+scheduler جدد و`setReuseAddress(true)` فلا بورت عالق. العملاء يرون DISCONNECTED ويعيدون الدخول يدويًا. (T15 و GUI-1.)

**13) لماذا JSON Lines؟** بسيط، قابل للقراءة، التهريب يضمن عدم وجود `\n` داخل الإطار فيصير «سطر = إطار». بديلها length-prefix؛ اخترنا الأول لسهولة التتبع.

**14) لماذا لم تستخدموا مكتبة JSON؟** بيئة التطوير لم تصل إلى Maven Central؛ وكتابة محلل صغير صارم أبسط للتسليم بلا dependencies. (الأسايمنت «يفضل» مكتبة لا يشترطها.)

**15) كيف تتجنبون duplicate login المتزامن؟** `UserDirectory.tryRegister` = `putIfAbsent` ذري (T04). والتنظيف `remove(user, session)` لا يحذف جلسة أحدث.

**16) ماذا لو سجّل المستخدم مرتين على نفس الاتصال؟** `ALREADY_LOGGED_IN` دون تغيير أي حالة (T03).

**17) كيف تتحققون من حد 1000 حرف؟ ولماذا ليس بايتات؟** `Protocol.charCount` يعد code points. الأسايمنت يقول «1,000 UTF-8 characters» أي محارف. في 3 أماكن: GUI، `MessengerClient.send`، وعند الاستقبال (السيرفر والعميل و`UdpPacket.decode`). (T17.)

**18) ماذا لو وصلت datagram خبيثة؟** `UdpPacket.decode` يرمي IAE ⇒ تُسجَّل وتُهمل والمستمع يكمل (T18).

**19) كيف يُربط ACK بالرسالة الصحيحة؟** بـ`messageId` + اسم الطرف المتوقع `p.peer`؛ ACK لرسالة أخرى أو من طرف آخر يُتجاهل. (حد: لا نتحقق من IP المصدر لأن الأجهزة متعددة الواجهات قد ترد من عنوان مختلف.)

**20) كيف تضمنون أن message ID فريد؟** `s<32 bit عشوائي>-<عداد>` لكل Session (T24: 200 معرف بلا تصادم؛ T21: جلسة جديدة ⇒ بادئة جديدة).

**21) ماذا لو فشلت الكتابة على TCP؟** `request()` يكمل الـfuture استثنائيًا ويغلق الاتصال؛ لا تظهر Sent. (`TcpLink.request`.)

**22) كيف تعملون مع عدة عملاء على نفس الجهاز؟** `UdpEngine.open` يجرب 6000، 6001…؛ `BindException` ⇒ التالي. (في اللقطات: sara=6000, adam=6001, ali=6002.)

**23) ماذا لو فشل فتح UDP؟** `doLogin` يلتقط الاستثناء ويعرض الرسالة؛ لا TCP يُفتح، ولا تسجيل ناقص.

**24) لماذا يُفتح UDP قبل LOGIN؟** لأن LOGIN يحمل `udpPort` الحقيقي؛ ولو سجلنا ثم فشل الربط لبقي إعلان خاطئ.

**25) كيف تمنعون إرسال رسائل قديمة بعد Login جديد؟** `teardown` يلغي كل Pending ويغلق المؤقتات والمقبس؛ كل Login ينشئ `Session` جديدة؛ والجلسة القديمة `closed` فلا تبثّ أحداثًا (T21).

**26) لماذا outbox وكاتب وحيد في السيرفر؟** كي لا تتداخل إطارات من عدة خيوط، وكي لا يعلّق عميل بطيء البثّ (امتلاء الطابور ⇒ فصل ذلك العميل). (T25.)

**27) ما حدود الأمان؟** مصادقة وبيانات بلا تشفير (مسموح للأسايمنت)، لا TLS، انتحال اسم المرسل في UDP ممكن على شبكة غير موثوقة. سجلنا ذلك في التقرير.

**28) ما الفرق بين `LOGOUT` والانقطاع؟** LOGOUT يرسل أمرًا ويستلم OK ثم يغلق بهدوء؛ الانقطاع يُكتشف بـEOF/timeout. كلاهما يمر بـ`cleanup` نفسه فيحدّث الدليل.

**29) أين يُحفظ سجل المحادثات؟** `ConversationStore` في الذاكرة فقط، 200 رسالة/محادثة (المطلوب ≥50)، لا يبقى بعد الإغلاق (مسموح).

**30) كيف اختبرتم المشروع؟** 23 اختبار تكامل بمقابس حقيقية (T01–T25) و35 فحصًا على نوافذ Swing الحقيقية (GuiScenario) مع لقطات؛ كلها نجحت مرتين. الباقي يدويًا: بناء Maven، جهازين/شبكتين، لوحة المفاتيح الفعلية، فيديو بصوتك (`TestCases.pdf`).

**31) كيف تحاكون ضياع UDP؟** خانة «TEST: simulate UDP packet loss» ⇒ `MessengerClient.setUdpReceiveEnabled(false)` ⇒ `UdpEngine.handle` يسقط الحزمة قبل فك التشفير (لا ACK ولا عرض)، وTCP يبقى متصلًا، والحالة تظهر PAUSED.

**32) ما سلوك الواجهة عند تغيّر الدليل أثناء اختيار جهة اتصال؟** نعيد اختيارها **بالاسم**؛ إن غادرت تبقى المحادثة ظاهرة «Offline» ويُسمح بالإرسال لإظهار الخطأ الواضح.

**33) لماذا Send ما زال فعّالًا لمستخدم غير متصل؟** مقصود: الأسايمنت يطلب إظهار خطأ واضح عند الإرسال لمستخدم غير متصل. يُعطَّل Send عند عدم اختيار مستلم أو نص فارغ/طويل أو انقطاع السيرفر.

**34) ما هو `shade` ولماذا؟** يدمج `common` داخل jar العميل/السيرفر ليعمل `java -jar` وحده.

## 9. ربط الأجزاء بمتطلبات الأسايمنت
| الجزء | مطلوب رسميًا؟ | ملاحظة |
|---|---|---|
| TCP control plane، LOGIN/LOGOUT/USERS/PRESENCE/PING | نعم (§3,§6) | |
| UDP P2P بلا مرور بالسيرفر + CHAT_UDP/ACK_UDP | نعم | |
| Retry مرتين/1 ث، dedup، IDs فريدة | نعم (إلزامي) | |
| TCP via Server + مؤشر التبديل | نعم | |
| ≥5 عملاء، 3 نوافذ | نعم | اختبرنا 6 |
| حسابات بهاش في ملف (≥4) | نعم | استخدمنا PBKDF2 (قرار تصميم) |
| GUI العميل والسيرفر وفق §5 | نعم (تصميم إرشادي) | |
| حد 1000 محرف، تحقق عند الاستقبال | الحد رسمي؛ التحقق عند الاستقبال **قرار تصميم** | |
| أمر `PEER` لاكتشاف النظير | الاكتشاف مطلوب؛ الاسم **إضافتنا** | |
| `SERVER_SHUTDOWN`, `ANNOUNCEMENT` (حدث) | الإعلان مطلوب (GUI السيرفر)؛ التسمية منا | |
| JSON Lines + محلل خاص | JSON Lines اقتراح رسمي؛ المحلل الخاص **قرار تصميم** | |
| outbox + كاتب وحيد، sweepStale، DedupCache المحدود | قرارات تصميم لدعم المتطلبات | |
| وضع محاكاة ضياع UDP | مطلوب (تعطيل الاستقبال مؤقتًا) | |
| Tailscale/ZeroTier | مطلوب (حل خارجي) | **غير مختبر هنا** |
| PDF/README/GroupInfo/فيديو | مطلوب (§8) | الفيديو الصوتي والأسماء: متبقٍ عليكما |
