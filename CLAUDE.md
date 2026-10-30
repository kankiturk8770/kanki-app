# Kanki VPN (اپ اندروید) — راهنمای Claude

اپ اندروید کانکی: Kotlin + Jetpack Compose، پکیج `cafe.kankipro.vpn`. همه‌ی کانفیگ‌ها را از اشتراک پنل کانکی
(ریپوی `kanki-panel`، تابع `sub_data` در `src/http/api.rs`) می‌گیرد. هر فیلد تازه‌ی اشتراک باید هر دو طرف
هماهنگ باشد.

## نقشه‌ی کد (`app/src/main/java/cafe/kankipro/vpn/`)
| کار | فایل |
|---|---|
| همه‌ی وضعیت برنامه، انتخاب سرور/کانفیگ، اتصال | `AppState.kt` (بزرگ‌ترین فایل) |
| خواندن اشتراک از پنل (سرورها، لینک‌ها، `@tunnel`) | `SubscriptionApi.kt` |
| اتصال صفحه‌ها به `AppState` | `Screens.kt` |
| صفحه‌ی اتصال (خانه) | `HomeUi.kt` |
| حالت‌ها (هوشمند، گیمینگ، ضد فیلتر، DNS) | `Modes.kt` (منطق)، `ModesUi.kt` (ظاهر) |
| تب سرورها | `ServersUi.kt` |
| تنظیمات و تم‌ها | `SettingsUi.kt`، رنگ‌ها و تم در `Ui.kt`، اجزای مشترک در `Kit.kt` |
| موتور WireGuard/AmneziaWG | `VpnEngine.kt`، `Tunnel.kt` |
| Hysteria2 / Xray / KANKI MIX / microtun | `Hysteria.kt` / `Xray.kt` / `Kmix.kt` / `Mtun.kt` |
| پینگ واقعی هر کانفیگ | `Probe.kt`، `WgHello.kt`، `KmixHello.kt`، `Tools.kt` |
| چت اضطراری | `Emergency.kt`، `EmergencyUi.kt`، `EmergencyNotify.kt` |
| DNS کانکی (رفع تحریم) | `DnsMode.kt` |
| دروازه‌ی برنامه‌ها: «فقط بازی» گیمینگ (KMIX/microtun)، DNS با بستن بقیه | `AppGate.kt`، `applyGameOnly` در `Tunnel.kt`، `GameApps` در `Extras.kt` |
| سقف فریم انیمیشن‌ها (گوشی ضعیف ۳۰، بقیه ۶۰) | `Motion` و `motionTime()` در `Kit.kt` |
| ترجمه (فارسی → انگلیسی) | `I18n.kt` |
| اسم کشور، پرچم، برچسب تانل/مستقیم | `Names.kt` |
| نقشه | `MapView.kt`، `WorldMap.kt` |
| ربات راهنما (فقط متن) | `Guide.kt` (`guideSteps`، `GuideOverlay`، `robotAlertFor`) |
| تست سرعت | `Speed.kt`، `SpeedUi.kt` |

## هسته‌های Go
- `kmix-core/` هسته‌ی KANKI MIX (`libkmix.so`)، تست: `cd kmix-core && go test ./...`
- `mtun-core/` هسته‌ی microtun (`libmtun.so`)، تست: `cd mtun-core && go test ./...`
- هسته‌ی Hysteria و فونت‌ها و نقشه موقع ساخت در CI دانلود می‌شوند (`.github/workflows/build.yml`).

## نسخه‌ی ویندوز (`windows/`)
- سرویس Go (`windows/service`، اجرا به‌عنوان SYSTEM: Wintun + amneziawg-go، مسیرها، WFP) و برنامه‌ی WPF
  (`windows/ui/KankiVPN`، کاربر عادی) که با JSON خط‌به‌خط روی `\\.\pipe\KankiVPN` حرف می‌زنند. نقشه‌ی کامل در `windows/README.md`.
- اشتراک را `windows/service/internal/sub` می‌خواند (مثل `SubscriptionApi.kt`): هر تغییر شکل صفحه‌ی اشتراک پنل هر دو را
  عوض می‌کند. فقط WireGuard و AmneziaWG.
- تست: `cd windows/service && go test -race ./...` و `GOOS=windows go vet ./...`. ساخت C# و تست‌های ادمین (کارت شبکه، WFP)
  فقط در CI (`.github/workflows/windows.yml`). متن UI در `Strings.cs` (انگلیسی ← فارسی). نسخه: `VERSION` در همان workflow،
  `Version` در `KankiVPN.csproj` و `windows/CHANGES.md`.

## ساخت و تست
- CI (`build.yml`) روی هر push فایل APK می‌سازد؛ `shots.yml` عکس صفحه‌ها (`app/src/test/.../Shots.kt`).
- ساخت محلی به Android SDK نیاز دارد که در محیط ابری نیست؛ پس بعد از push نتیجه‌ی CI را چک کن.

## قوانین هر تغییر
1. نسخه در `app/build.gradle.kts` بالا برود: `versionCode` یکی بیشتر و `versionName`.
2. یک بخش تازه‌ی فارسی بالای `CHANGES.md` (`# تغییرات نسخه‌ی X.Y.Z`) و «تازه‌های نسخه» کوتاه در `README.md`.
3. متن‌های UI فارسی‌اند؛ ترجمه‌ی انگلیسی هر متن تازه در `I18n.kt`.
4. کامنت‌ها مثل کد اطراف (فارسی). بدون اسم مدل در کامنت یا کامیت.
5. کار که تمام شد: commit، push به برنچ کار و Pull Request بساز. **قبل از مرج به main یک بار از صاحب ریپو بپرس**
   (main مستقیم روی سرورها / کاربران می‌رود). اگر CI قرمز است، اول درستش کن.
6. اگر ساختار پروژه عوض شد (فایل تازه، فیلد تازه‌ی اشتراک)، همین `CLAUDE.md` را هم به‌روز کن.

## زبان
صاحب پروژه فارسی‌زبان است و دارد برنامه‌نویسی یاد می‌گیرد: جواب‌ها فارسی، کوتاه و ساده.

## طراحی
اسکیل `frontend-design` (رسمی Anthropic) در `.claude/skills/frontend-design/`؛ برای هر کار UI اول آن را بخوان.
عکس صفحه‌ها بعد از push در شاخه‌ی `shots` (کار `shots.yml`).

## گزارش کارها

### 2026-10-29 — نسخه‌ی ۱.۲۲.۰
خواسته‌ی صاحب اپ: انیمیشن‌های بی‌لگ، تم زرد، گیمینگ استاندارد، صفحه‌ی سرورها (بدون تیک‌های اضافه، سربرگ با لوگو، باگ
بیرون ماندن کانفیگ‌ها، کارت‌های مدرن)، تست سرعت UDP، هوشمند بدون KMIX/microtun با رفتن به تانل، فایروال «فقط بازی».

انجام شد (جزئیات در `CHANGES.md`):
1. تم زرد پیش‌فرض (`Ui.kt`، مهاجرت یک‌باره `theme122` در `AppState`).
2. انیمیشن‌ها: `Motion`/`motionTime` (Kit.kt)، دکمه‌ی اتصال با `drawWithCache`، نقشه هنگام بزرگ‌نمایی عکس آماده را بزرگ می‌کند (`WorldMap.kt`).
3. گیمینگ: کارت‌های پروتکل دوتا در ردیف (`ModesUi.kt` `FamilyCard`).
4. سرورها: `ServersHero` با لوگوی `res/drawable-nodpi/kanki_logo.webp`، `CfgCell` و `SignalBars` تازه، `key()` برای کارت‌ها و ردیف‌ها.
5. تست سرعت: `UdpSeries` (Speed.kt)، `Probe.kmixSeries`، `udpLatency()` در AppState.
6. هوشمند: `GAME_ONLY` و ترتیب تازه در `runSmart`.
7. فایروال: `AppGate` (reject: RST/ICMP فقط برای صاحب معلوم، کش منفی ۲۵۰ms، اولویت رشته‌ها)، `gameOnly`/`gameApps` در AppState، `GameAppsPanel`، سوییچ در `GameSheet`.
8. ربات راهنما: صدا و فایل‌هایش حذف (`GuideVoice.kt`، `res/raw/guide_*.mp3`)، متن‌ها از نو، حباب تازه (`GuideBubble`، `GuideBody`، `GuidePoint`)، قدم «فقط بازی» (`guideTarget("game_only")`).
10. صفحه‌ی ورود: نمونه‌ی کد `kanki-…` جایش ۱۰ خط تیره (`Screens.kt`، `EmergencyUi.kt`). پاک‌سازی ۱۹ تابع بی‌استفاده.
12. اشتراک: `loadAccount` جواب بی‌کانفیگ را جای نسخه‌ی سالم ذخیره نمی‌کند؛ `loadInfos` و `startupLoad` با کانفیگ خراب کرش
    نمی‌کنند؛ `syncLock` (بروزرسانی دستی و ۳ ساعته هم‌زمان نه)؛ `pruneOld` بعد از هر بروزرسانی سالم و ورود.
13. چت اضطراری بازطراحی (`EmergencyUi.kt` `EmergencyScreen`، `Bubble`)؛ استیکر کوچک (`Stickers.SIZE` = 320، نمایش ۱۱۲dp).
14. چت اضطراری: نشان تازه (`EmergencyIcon`)، صدای پیام (`chat_in`/`chat_out`)، بررسی سریع ۹۰ ثانیه‌ای (`EmergencyStore.watch`/`poll`)؛ متن‌های بی‌طرف به‌جای «فیلترینگ / اینترنت ملی».
11. بازبینی موتورها (WireGuard/AmneziaWG با GoBackend، Hysteria2/Xray با tun2socks، KANKI MIX، microtun، DNS): فقط یک VPN
    هم‌زمان بالاست (`VpnEngine.up` اول موتور دیگر را پایین می‌آورد)، آی‌پی سرور از مسیرهای تونل بیرون است، IPv6 بدون
    آدرس در تونل بسته است (نه نشت، نه معطلی)، اتصال فقط با جواب واقعی از داخل تونل «متصل» می‌شود (`awaitHandshake`)،
    عوض شدن شبکه سلامت تونل را می‌سنجد و لازم شد دوباره وصل می‌کند (`watchNetwork`، `revive`). ایراد تازه‌ای پیدا نشد.
9. بازبینی دوباره‌ی اتصال‌ها: یک ایراد پیدا شد و درست شد (جواب RST/ICMP به اتصالی که صاحبش لحظه‌ای معلوم نشده بود می‌توانست بازی را قطع کند).

تست: محیط ابری Android SDK ندارد؛ ساخت و عکس‌ها در CI (`build.yml`، `shots.yml`).
تصمیم‌ها: تست پهنای باند روی UDP بدون سرور UDP مخصوص ممکن نیست؛ دانلود/آپلود همان Speedtest است که وقتی وصلی از تونل UDP
می‌رود. فایروال فقط با دروازه‌ی داخل برنامه شدنی است (اندروید اجازه‌ی بستن بقیه را جور دیگری نمی‌دهد)؛ کار هر بسته فقط یک
جست‌وجوی جدول است.
مانده: تست فایروال روی گوشی واقعی با پابجی (ورود، پینگ، مسابقه).
