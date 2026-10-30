# Kanki VPN برای ویندوز

کلاینت ویندوزی کانکی: همان اشتراک پنل (`/sub/<کد>`)، همان سرورها، با معماری استاندارد ویندوز.

## معماری

```
 KankiVPN.exe  (WPF، کاربر عادی، بدون ادمین) — ظاهر همان اپ اندروید، تم زرد و زرشکی
      │   JSON خط‌به‌خط روی \\.\pipe\KankiVPN
      ▼
 kanki-svc.exe (Windows Service، SYSTEM)
   ├─ core      اشتراک، تنظیمات، اتصال، نگهبان اتصال، اتصال دوباره، پینگ همه
   ├─ cores     لینک‌های KANKI MIX / microtun / VLESS / Hysteria2، کانفیگ Xray و Hysteria، پینگ واقعی هر پروتکل
   ├─ tunnel    Wintun + هسته‌ی هر پروتکل، آدرس، مسیرها، DNS
   └─ firewall  WFP: کیل سوییچ، جلوگیری از نشت DNS و IPv6، تقسیم ترافیک
 xray.exe / hysteria.exe  برنامه‌های رسمی (همان نسخه‌ی اندروید)، فقط SOCKS روی 127.0.0.1، در Job Object سرویس
```

| پروتکل | موتور روی ویندوز |
|---|---|
| WireGuard، AmneziaWG (۲) | `amneziawg-go` روی Wintun |
| KANKI MIX | همان هسته‌ی Go اندروید (`kmix-core/mobile`، `StartDevice`) روی Wintun |
| microtun | همان کلاینت Go اندروید (`mtun-core/mtun`) روی Wintun |
| VLESS | `xray.exe` + tun2socks روی Wintun (مثل اندروید: Xray + hev-socks5-tunnel) |
| Hysteria2 | `hysteria.exe` + tun2socks روی Wintun |
| OpenVPN | عمداً نیست |

| معیار | چطور |
|---|---|
| کارت شبکه | Wintun با GUID ثابت (همه‌ی پروتکل‌ها یک کارت «Kanki VPN»). |
| مسیریابی و WFP | مسیرها = AllowedIPs منهای «بیرون تونل» (آدرس سرور، LAN، رنج‌های استثنا). کیل سوییچ WFP: همه بسته جز تونل، بسته‌های خود سرویس و هسته‌هایش، loopback، DHCP، NDP و رنج‌های مجاز. تقسیم ترافیک بر اساس IP/رنج. |
| نشت DNS و IPv6 | DNS فقط به DNS تونل (پورت ۵۳ بقیه بسته). IPv6 بیرون تونل بسته (پروتکل‌های لینکی IPv6 ندارند). |
| سرویس جدا | سرویس SYSTEM همه‌ی کار شبکه را دارد؛ برنامه فقط از پایپ دستور می‌دهد. پایپ: کاربر واردشده خواندن/نوشتن، شبکه ممنوع؛ برنامه آن طرف پایپ را تأیید می‌کند. |

## امنیت داده‌ها
- **DPAPI:** فایل وضعیت (کد اشتراک، لینک‌ها، شناسه‌ی دستگاه) با DPAPI ویندوز زیر حساب SYSTEM مهر می‌شود؛ کپی آن روی کامپیوتر
  دیگر یا خواندنش با کاربر دیگر بی‌فایده است. فایل نسخه‌ی ۱.۰.۰ یک بار خوانده و با ذخیره‌ی بعدی مهر می‌شود.
- **شناسه‌ی دستگاه:** یک بار برای هر نصب ساخته می‌شود و با هر درخواست به پنل می‌رود (`X-Kanki-Device`)؛ microtun هم با همین
  شناسه یک دستگاه ثابت روی سرور می‌ماند.
- **کانفیگ رمزشده (E2EE، روی TLS):** برنامه با `X-Kanki-Seal: kseal1` می‌گوید کانفیگ مهرشده می‌فهمد. پنلی که آن را بشناسد جواب
  می‌دهد `kseal1.<base64url(nonce12 | AES-256-GCM)>` با کلید `HKDF-SHA256(کد اشتراک، salt = شناسه‌ی دستگاه، "kanki sealed config v1")`
  و AAD = `kseal1`. فقط پنل و همین دستگاه کلید را دارند؛ کش CDN یا سرور واسط ایران چیزی نمی‌خواند. باز کردن فقط در حافظه است
  (`internal/sub/seal.go`، تابع `Seal` مرجع پیاده‌سازی پنل). پنل فعلی هنوز متن ساده می‌فرستد و آن هم قبول است.
- **کلیدها در حافظه:** کلید باز کردن بعد از استفاده صفر می‌شود؛ کانفیگ Xray از stdin داده می‌شود و روی دیسک نمی‌رود؛ کانفیگ
  Hysteria (که stdin نمی‌خواند) فقط چند ثانیه در پوشه‌ی مخصوص SYSTEM می‌ماند.
- **هیچ اسکریپتی اجرا نمی‌شود:** کانفیگ با `PreUp/PostUp/...` رد می‌شود.

## محافظت از کد
- سرویس با **garble** ساخته می‌شود (`-literals -tiny`): اسم‌های پکیج‌ها، تابع‌ها و رشته‌های کد ما در فایل نهایی پیدا نیست.
- برنامه با **Obfuscar** (متن‌باز): اسم‌ها و رشته‌های خصوصی عوض می‌شوند؛ نوع‌های عمومی که XAML و JSON با اسم پیدا می‌کنند می‌مانند.
- هیچ پکری (UPX و مانند آن) استفاده نمی‌شود، پس Windows Defender چیز مشکوکی نمی‌بیند. Dotfuscator تجاری است و مجوز
  می‌خواهد؛ جایش Obfuscar است.
- برای انتشار عمومی امضای کد (Authenticode) لازم است؛ فایل‌ها هنوز امضا ندارند (SmartScreen هشدار می‌دهد).

### چیزهایی که این نسخه ندارد
- تقسیم ترافیک بر اساس برنامه (و «فقط بازی» KANKI MIX/microtun): در ویندوز درایور کرنل امضاشده لازم دارد؛ عمداً حذف شد.
- چت اضطراری و ربات راهنما: عمداً نیستند. OpenVPN: عمداً نیست.
- پرچم کشورها: ویندوز ایموجی پرچم ندارد؛ کد کشور در دایره نشان داده می‌شود.

## پوشه‌ها
| کار | فایل |
|---|---|
| شروع سرویس، install/uninstall/debug | `service/cmd/kanki-svc/main_windows.go` |
| خواندن کانفیگ WG/AWG و ساختن UAPI | `service/internal/conf/` |
| مسیرها و تقسیم ترافیک | `service/internal/route/` |
| خواندن اشتراک پنل (مثل `SubscriptionApi.kt`) | `service/internal/sub/` |
| مدیریت اتصال، کیل سوییچ هنگام اتصال دوباره | `service/internal/core/` |
| پروتکل پایپ | `service/internal/ipc/` |
| تنظیمات و فایل وضعیت | `service/internal/state/` |
| WFP (برگرفته از WireGuard for Windows، MIT) | `service/internal/firewall/` (`blocker.go` بازنویسی ما) |
| لینک‌ها، کانفیگ Xray/Hysteria، پینگ هر پروتکل | `service/internal/cores/` |
| کانفیگ رمزشده (kseal1) | `service/internal/sub/seal.go` |
| DPAPI | `service/internal/state/dpapi_windows.go` |
| Wintun، هسته‌ها، آدرس، مسیر، DNS | `service/internal/tunnel/` (`cores_windows.go`: KANKI MIX، microtun، Xray/Hysteria + tun2socks) |
| برنامه (WPF) | `ui/KankiVPN/` (`MainWindow.xaml`، تم‌ها `Theme.cs`، تست سرعت `Speed.cs`، کشورها `Countries.cs`، ترجمه `Strings.cs`) |
| ظاهر کشیده‌شده (کپی کد اندروید) | `Gfx.cs` (رنگ، گرادیان، ساعت انیمیشن)، `Draw.cs` (شیشه، آیکن‌ها، لوگو، گیج، ضربان پینگ، پس‌زمینه)، `Orb.cs` (دکمه‌ی اتصال و تعقیب گیمینگ)، `WorldMap.cs` (نقشه) |
| داده‌ی نقشه و پرچم‌ها | `Assets/countries.bin` (از Natural Earth با `tools/packmap.py`)، `Assets/flags/*.png` (پرچم‌های Noto) |
| فایل نصبی (Inno Setup) و تست نصب | `installer/` |

## ساخت و تست
- `cd windows/service && go test -race ./...` (روی لینوکس هم). `GOOS=windows go vet ./...` برای بخش ویندوز.
- CI (`.github/workflows/windows.yml`): تست روی لینوکس، بعد روی ویندوز: ساخت سرویس، تست واقعی کارت شبکه / مسیر / DNS /
  WFP با دسترسی ادمین، ساخت برنامه، عکس صفحه‌ها (`KankiVPN.exe --shot <پوشه>`)، نصب سرویس و گفت‌وگو از راه پایپ،
  و ساخت `KankiVPN-Setup-x.y.z.exe`. خروجی‌ها در Artifacts همان اجرا.
- اجرای دستی روی ویندوز (ادمین): `kanki-svc.exe debug` در یک ترمینال، بعد `KankiVPN.exe`.
