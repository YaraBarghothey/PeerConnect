<!-- PeerConnect - YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 -->
# سيناريو فيديو العرض (3–5 دقائق) — PeerConnect

> **حالة الفيديو:** ملف `docs/demo_automated_screen_recording.mp4` تسجيل **آلي حقيقي** (3 د 25 ث) للنوافذ وهي تعمل (سيرفر + 3 عملاء + عميل رابع للمحاولة المكررة) بلا صوت.
> **المتبقي على الطالبتين:** تسجيل فيديو بصوتكما (`DemoVideo.mp4`) على جهازكما باتباع هذا السيناريو — هذه خطوة لازمة لأن المناقشة تتطلب أن تشرحا بصوتكما.

**التحضير (قبل التسجيل):** `build.bat` ← `run_server.bat` ← ثلاثة أوامر `run_client.bat sara|adam|ali`. رتّبا النوافذ بحيث تظهر الثلاثة معًا (يمكن تصغيرها). شغّلا مسجّل الشاشة (OBS / Win+G).

| الوقت | ماذا نفتح / نضغط | ماذا نرسل / نقول | الدليل الظاهر على الشاشة |
|---|---|---|---|
| 0:00–0:20 | نافذة السيرفر | «هذا PeerConnect: سيرفر TCP للدليل والمصادقة، والرسائل المباشرة تذهب UDP بين العملاء.» اضغطا **START** | الحالة تتحول إلى `LISTENING on 0.0.0.0:8888`، السجل: `Server listening…` |
| 0:20–0:50 | في كل عميل: كلمة المرور ثم **Login** | sara / adam / ali | جدول السيرفر: 3 مستخدمين مع IP وUDP port مختلف؛ `Connected: 3`؛ قائمة الأصدقاء تمتلئ تلقائيًا |
| 0:50–1:10 | عميل رابع: `run_client.bat sara` ثم Login | «تسجيل دخول مكرر» | رسالة `already logged in on another client`؛ سجل السيرفر `duplicate login rejected` |
| 1:10–1:50 | sara: اختاري adam، وضع **UDP P2P**، اكتبي رسالة، Enter | «الرسالة تذهب مباشرة إلى بورت adam» | عند adam: شارة حمراء (unread) ثم الرسالة؛ عند sara: **✓✓ Acknowledged**؛ **سجل السيرفر لا يُظهر أي شيء** (لا relay) |
| 1:50–2:20 | sara: اختاري ali، بدّلي إلى **TCP via Server**، أرسلي | «هنا تمر عبر السيرفر» | سجل السيرفر `[TCP RELAY] sara -> ali`؛ عند ali تظهر الرسالة؛ adam لا يستقبل شيئًا؛ حالة sara: `Sent – relayed by the server (NOT a read receipt)` |
| 2:20–2:40 | رسالة عربية متعددة الأسطر مع اقتباس عبر UDP | — | تظهر سليمة RTL عند المستقبل |
| 2:40–3:00 | adam: غيّر الحالة إلى Busy | — | النقطة الحمراء عند sara وali؛ ثم من sara: **New conversation → omar** وأرسلي | رسالة `Failed: user 'omar' is offline` والواجهة لا تتجمد |
| 3:00–3:40 | adam: فعّل **TEST: simulate UDP packet loss** ثم sara ترسل UDP إلى adam | «نحاكي ضياع الحزم دون قطع TCP» | عند adam: `PAUSED`؛ عند sara: `attempt 1/3 → retransmitted 2/3 → 3/3 → Failed (no ACK)` بعد ≈3 ث؛ adam لا يعرض شيئًا؛ ثم أوقفا الاختبار وأعيدا الإرسال ← Acknowledged |
| 3:40–4:10 | أغلقي نافذة ali بعنف (إنهاء العملية من Task Manager) | «انقطاع غير متوقع» | السيرفر يسجل `ali removed (… EOF)` ويستمر؛ قائمة sara وadam تحدّث ali إلى غير متصل |
| 4:10–4:40 | السيرفر: **STOP** ثم **START** | «لا بورت عالق» | العملاء `DISCONNECTED`؛ بعد START يعمل الدخول من جديد |
| 4:40–5:00 | جدول المقارنة TCP/UDP (من ProjectReport) | اشرحا الفرق عمليًا | — |
