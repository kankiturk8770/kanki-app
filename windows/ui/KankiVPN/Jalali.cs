using System;
using System.Globalization;

namespace KankiVPN
{
    /// <summary>Dates and digits as the Android app shows them (PanelTime.jalali, Persian digits).</summary>
    public static class Jalali
    {
        static readonly string[] Months = { "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند" };
        static readonly PersianCalendar Cal = new();

        public static string Date(long unix)
        {
            var t = DateTimeOffset.FromUnixTimeSeconds(unix).ToLocalTime().DateTime;
            if (!Strings.Persian) return t.ToString("d MMM yyyy, HH:mm", CultureInfo.InvariantCulture);
            return Fa($"{Cal.GetDayOfMonth(t)} {Months[Cal.GetMonth(t) - 1]} {Cal.GetYear(t)}، {t:HH:mm}");
        }

        /// <summary>Latin digits to Persian ones in Persian mode.</summary>
        public static string Fa(string s)
        {
            if (!Strings.Persian) return s;
            var c = s.ToCharArray();
            for (var i = 0; i < c.Length; i++)
                if (c[i] >= '0' && c[i] <= '9') c[i] = (char)('۰' + (c[i] - '0'));
            return new string(c);
        }

        /// <summary>"23 days and 4 hours", as PanelTime.remaining.</summary>
        public static string Remaining(long unix)
        {
            if (unix <= 0) return Strings.T("Unlimited");
            var diff = TimeSpan.FromSeconds(unix - DateTimeOffset.UtcNow.ToUnixTimeSeconds());
            if (diff <= TimeSpan.Zero) return Strings.T("Expired");
            var d = (int)diff.TotalDays;
            var h = diff.Hours;
            string r;
            if (d > 0 && h > 0) r = $"{d} {Strings.T("days")} {Strings.T("and")} {h} {Strings.T("hours")}";
            else if (d > 0) r = $"{d} {Strings.T("days")}";
            else if (h > 0) r = $"{h} {Strings.T("hours")}";
            else r = $"{Math.Max(1, (int)diff.TotalMinutes)} {Strings.T("minutes")}";
            return Fa(r);
        }
    }
}
