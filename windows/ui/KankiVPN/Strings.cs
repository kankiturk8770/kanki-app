using System.Collections.Generic;

namespace KankiVPN
{
    /// <summary>
    /// Every UI text is written in English in the code and goes through T();
    /// the Persian of each one is a line in the dictionary below.
    /// </summary>
    public static class Strings
    {
        public static bool Persian { get; set; } = true;

        public static string T(string en) => Persian && Fa.TryGetValue(en, out var fa) ? fa : en;

        static readonly Dictionary<string, string> Fa = new()
        {
            ["Kanki VPN"] = "کانکی VPN",
            ["Home"] = "اتصال",
            ["Servers"] = "سرورها",
            ["Settings"] = "تنظیمات",
            ["Log"] = "گزارش",
            ["Connect"] = "وصل شو",
            ["Disconnect"] = "قطع کن",
            ["Cancel"] = "لغو",
            ["Not connected"] = "وصل نیست",
            ["Connecting…"] = "در حال اتصال…",
            ["Connected"] = "وصل است",
            ["Reconnecting…"] = "اتصال دوباره…",
            ["Could not connect"] = "وصل نشد",
            ["Internet is blocked until the VPN is back (kill switch)."] = "تا برگشتن VPN اینترنت بسته است (کیل سوییچ).",
            ["Choose a server"] = "یک سرور انتخاب کن",
            ["Download"] = "دریافت",
            ["Upload"] = "ارسال",
            ["Time"] = "زمان",
            ["Kill switch on"] = "کیل سوییچ روشن",
            ["Kill switch off"] = "کیل سوییچ خاموش",
            ["Through Iran server"] = "از راه سرور ایران",
            ["Direct"] = "مستقیم",
            ["Unlimited"] = "نامحدود",
            ["days left"] = "روز مانده",
            ["Expired"] = "منقضی شده",
            ["GB left"] = "گیگ مانده",
            ["Add your subscription"] = "اشتراکت را وارد کن",
            ["Paste the subscription link or code you got from Kanki."] = "لینک یا کد اشتراکی که از کانکی گرفتی را این‌جا بگذار.",
            ["Subscription link or code"] = "لینک یا کد اشتراک",
            ["Add"] = "افزودن",
            ["Loading…"] = "در حال دریافت…",
            ["Refresh servers"] = "به‌روز کردن سرورها",
            ["Updated"] = "به‌روز شد",
            ["Sign out"] = "خروج از حساب",
            ["No servers for Windows in this subscription. WireGuard or AmneziaWG must be on for you in the panel."] =
                "در این اشتراک سروری برای ویندوز نیست. باید WireGuard یا AmneziaWG برایت در پنل روشن باشد.",
            ["The VPN service is not running. Reinstall Kanki VPN, or start the \"Kanki VPN\" service in Windows Services."] =
                "سرویس VPN اجرا نیست. کانکی VPN را دوباره نصب کن یا سرویس «Kanki VPN» را در Services ویندوز روشن کن.",
            ["The VPN service could not be verified. Reinstall Kanki VPN."] = "سرویس VPN تأیید نشد. کانکی VPN را دوباره نصب کن.",
            ["Protection"] = "محافظت",
            ["Kill switch"] = "کیل سوییچ",
            ["If the VPN drops, all internet stops until it is back. Nothing leaks outside the tunnel."] =
                "اگر VPN قطع شود، تا برگشتنش کل اینترنت قطع می‌ماند. هیچ چیزی بیرون از تونل نمی‌رود.",
            ["Block IPv6 outside the VPN"] = "بستن IPv6 بیرون از VPN",
            ["Stops IPv6 leaks on networks that have IPv6."] = "جلوی نشت IPv6 را در شبکه‌هایی که IPv6 دارند می‌گیرد.",
            ["DNS leak protection is always on: DNS only goes to the VPN's DNS servers."] =
                "جلوگیری از نشت DNS همیشه روشن است: DNS فقط به سرورهای DNS خود VPN می‌رود.",
            ["Allow local network"] = "اجازهٔ شبکهٔ محلی",
            ["Printers, file sharing and the router stay reachable."] = "پرینتر، اشتراک فایل و مودم در دسترس می‌مانند.",
            ["Split tunneling"] = "تقسیم ترافیک",
            ["Everything through the VPN"] = "همه از VPN",
            ["Everything except these addresses"] = "همه به‌جز این آدرس‌ها",
            ["Only these addresses"] = "فقط این آدرس‌ها",
            ["One IP or range per line, like 5.0.0.0/8"] = "هر خط یک IP یا رنج، مثل 5.0.0.0/8",
            ["The kill switch does not apply when only some addresses use the VPN."] = "وقتی فقط بعضی آدرس‌ها از VPN می‌روند، کیل سوییچ کار نمی‌کند.",
            ["Connection"] = "اتصال",
            ["DNS servers"] = "سرورهای DNS",
            ["Empty = the server's own DNS"] = "خالی = DNS خود سرور",
            ["MTU"] = "MTU",
            ["0 = automatic"] = "۰ = خودکار",
            ["Connect when the service starts"] = "وصل شدن با روشن شدن ویندوز",
            ["Open at sign-in"] = "باز شدن برنامه با ورود به ویندوز",
            ["Language"] = "زبان",
            ["Save settings"] = "ذخیرهٔ تنظیمات",
            ["Saved"] = "ذخیره شد",
            ["Account"] = "حساب",
            ["Copy log"] = "کپی گزارش",
            ["Copied"] = "کپی شد",
            ["Open"] = "باز کردن",
            ["Quit"] = "خروج",
            ["Kanki VPN is still running in the tray."] = "کانکی VPN هنوز کنار ساعت ویندوز روشن است.",
            ["Service"] = "سرویس",
        };
    }
}
