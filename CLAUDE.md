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
| ترجمه (فارسی → انگلیسی) | `I18n.kt` |
| اسم کشور، پرچم، برچسب تانل/مستقیم | `Names.kt` |
| نقشه | `MapView.kt`، `WorldMap.kt` |
| ربات راهنما و صدایش | `Guide.kt`، `GuideVoice.kt` |
| تست سرعت | `Speed.kt`، `SpeedUi.kt` |

## هسته‌های Go
- `kmix-core/` هسته‌ی KANKI MIX (`libkmix.so`)، تست: `cd kmix-core && go test ./...`
- `mtun-core/` هسته‌ی microtun (`libmtun.so`)، تست: `cd mtun-core && go test ./...`
- هسته‌ی Hysteria و فونت‌ها و نقشه موقع ساخت در CI دانلود می‌شوند (`.github/workflows/build.yml`).

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
