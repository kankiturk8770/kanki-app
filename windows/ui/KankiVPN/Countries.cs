using System.Collections.Generic;

namespace KankiVPN
{
    /// <summary>Country names, the usual data-centre city and its map position. Generated from the
    /// Android app (Names.kt flagTable and cityName, WorldMap.kt) so both apps name things alike.</summary>
    public static class Countries
    {
        public sealed record Info(string Fa, string En, string CityFa, string CityEn, double Lat, double Lon);

        public static readonly Dictionary<string, Info> All = new()
        {
            ["DE"] = new("آلمان", "Germany", "فرانکفورت", "Frankfurt", 50.1, 8.7),
            ["TR"] = new("ترکیه", "Turkey", "استانبول", "Istanbul", 41.0, 29.0),
            ["IR"] = new("ایران", "Iran", "تهران", "Tehran", 35.7, 51.4),
            ["NL"] = new("هلند", "Netherlands", "آمستردام", "Amsterdam", 52.4, 4.9),
            ["FI"] = new("فنلاند", "Finland", "هلسینکی", "Helsinki", 60.2, 24.9),
            ["FR"] = new("فرانسه", "France", "پاریس", "Paris", 48.9, 2.35),
            ["UK"] = new("انگلیس", "England", "", "", 0.0, 0.0),
            ["US"] = new("آمریکا", "America", "واشنگتن", "Washington", 39.0, -77.0),
            ["AE"] = new("امارات", "Dubai", "دبی", "Dubai", 25.2, 55.3),
            ["RU"] = new("روسیه", "Russia", "مسکو", "Moscow", 55.8, 37.6),
            ["SE"] = new("سوئد", "Sweden", "استکهلم", "Stockholm", 59.3, 18.1),
            ["CA"] = new("کانادا", "Canada", "تورنتو", "Toronto", 43.7, -79.4),
            ["PL"] = new("لهستان", "Poland", "ورشو", "Warsaw", 52.2, 21.0),
            ["AT"] = new("اتریش", "Austria", "وین", "Vienna", 48.2, 16.4),
            ["AM"] = new("ارمنستان", "Armenia", "ایروان", "Yerevan", 40.2, 44.5),
            ["SG"] = new("سنگاپور", "Singapore", "سنگاپور", "Singapore", 1.35, 103.8),
            ["JP"] = new("ژاپن", "Japan", "توکیو", "Tokyo", 35.7, 139.7),
            ["CH"] = new("سوئیس", "Switzerland", "زوریخ", "Zurich", 47.4, 8.5),
            ["CZ"] = new("چک", "Czech", "پراگ", "Prague", 50.1, 14.4),
            ["RO"] = new("رومانی", "Romania", "بخارست", "Bucharest", 44.4, 26.1),
            ["BG"] = new("بلغارستان", "Bulgaria", "صوفیه", "Sofia", 42.7, 23.3),
            ["UA"] = new("اوکراین", "Ukraine", "کی‌یف", "Kyiv", 50.45, 30.5),
            ["KZ"] = new("قزاقستان", "Kazakhstan", "آلماتی", "Almaty", 43.2, 76.9),
            ["GE"] = new("گرجستان", "Georgia", "تفلیس", "Tbilisi", 41.7, 44.8),
            ["AZ"] = new("جمهوری آذربایجان", "Azerbaijan", "باکو", "Baku", 40.4, 49.9),
            ["KR"] = new("کره", "Korea", "سئول", "Seoul", 37.6, 127.0),
            ["HK"] = new("هنگ کنگ", "Hongkong", "هنگ کنگ", "Hong Kong", 22.3, 114.2),
            ["AU"] = new("استرالیا", "Australia", "سیدنی", "Sydney", -33.9, 151.2),
            ["BR"] = new("برزیل", "Brazil", "سائوپائولو", "Sao Paulo", -23.5, -46.6),
            ["IE"] = new("ایرلند", "Ireland", "دوبلین", "Dublin", 53.35, -6.26),
            ["DK"] = new("دانمارک", "Denmark", "کپنهاگ", "Copenhagen", 55.7, 12.6),
            ["LT"] = new("لیتوانی", "Lithuania", "ویلنیوس", "Vilnius", 54.7, 25.3),
            ["LV"] = new("لتونی", "Latvia", "ریگا", "Riga", 56.95, 24.1),
            ["EE"] = new("استونی", "Estonia", "تالین", "Tallinn", 59.4, 24.75),
            ["PT"] = new("پرتغال", "Portugal", "لیسبون", "Lisbon", 38.7, -9.1),
            ["GR"] = new("یونان", "Greece", "آتن", "Athens", 37.98, 23.7),
            ["HU"] = new("مجارستان", "Hungary", "بوداپست", "Budapest", 47.5, 19.04),
            ["RS"] = new("صربستان", "Serbia", "بلگراد", "Belgrade", 44.8, 20.5),
            ["SA"] = new("عربستان", "Saudi", "ریاض", "Riyadh", 24.7, 46.7),
            ["QA"] = new("قطر", "Qatar", "دوحه", "Doha", 25.3, 51.5),
            ["KW"] = new("کویت", "Kuwait", "کویت", "Kuwait City", 29.4, 48.0),
            ["IQ"] = new("عراق", "Iraq", "بغداد", "Baghdad", 33.3, 44.4),
            ["VN"] = new("ویتنام", "Vietnam", "هانوی", "Hanoi", 21.0, 105.8),
            ["MD"] = new("مولداوی", "Moldova", "کیشیناو", "Chisinau", 47.0, 28.85),
            ["LU"] = new("لوکزامبورگ", "Luxembourg", "لوکزامبورگ", "Luxembourg", 49.6, 6.13),
            ["CY"] = new("قبرس", "Cyprus", "نیکوزیا", "Nicosia", 35.17, 33.36),
            ["SK"] = new("اسلواکی", "Slovakia", "براتیسلاوا", "Bratislava", 48.15, 17.1),
            ["SI"] = new("اسلوونی", "Slovenia", "لیوبلیانا", "Ljubljana", 46.05, 14.5),
            ["HR"] = new("کرواسی", "Croatia", "زاگرب", "Zagreb", 45.8, 15.98),
            ["BA"] = new("بوسنی", "Bosnia", "", "", 43.86, 18.4),
            ["AL"] = new("آلبانی", "Albania", "", "", 41.33, 19.8),
            ["MK"] = new("مقدونیه شمالی", "Macedonia", "", "", 42.0, 21.43),
            ["XK"] = new("کوزوو", "Kosovo", "", "", 42.66, 21.17),
            ["MT"] = new("مالت", "Malta", "والتا", "Valletta", 35.9, 14.5),
            ["BY"] = new("بلاروس", "Belarus", "مینسک", "Minsk", 53.9, 27.56),
            ["LI"] = new("لیختن‌اشتاین", "Liechtenstein", "", "", 47.14, 9.52),
            ["AD"] = new("آندورا", "Andorra", "", "", 42.5, 1.52),
            ["OM"] = new("عمان", "Oman", "مسقط", "Muscat", 23.6, 58.4),
            ["BH"] = new("بحرین", "Bahrain", "منامه", "Manama", 26.2, 50.6),
            ["JO"] = new("اردن", "Jordan", "", "", 31.95, 35.9),
            ["EG"] = new("مصر", "Egypt", "", "", 30.04, 31.24),
            ["PK"] = new("پاکستان", "Pakistan", "", "", 24.86, 67.0),
            ["TW"] = new("تایوان", "Taiwan", "", "", 25.03, 121.56),
            ["ZA"] = new("آفریقای جنوبی", "South Africa", "", "", -26.2, 28.05),
            ["MX"] = new("مکزیک", "Mexico", "", "", 19.43, -99.13),
            ["AR"] = new("آرژانتین", "Argentina", "", "", -34.6, -58.38),
            ["CL"] = new("شیلی", "Chile", "", "", -33.45, -70.67),
            ["NZ"] = new("نیوزیلند", "New Zealand", "", "", -36.85, 174.76),
            ["UZ"] = new("ازبکستان", "Uzbekistan", "", "", 41.3, 69.24),
            ["TM"] = new("ترکمنستان", "Turkmenistan", "", "", 37.95, 58.38),
            ["TJ"] = new("تاجیکستان", "Tajikistan", "", "", 38.56, 68.79),
            ["KG"] = new("قرقیزستان", "Kyrgyzstan", "", "", 42.87, 74.59),
        };

        public static Info? Of(string? iso) => iso != null && All.TryGetValue(iso.ToUpperInvariant(), out var i) ? i : null;

        public static string Name(string? iso, string fallback)
        {
            var i = Of(iso);
            return i == null ? fallback : Strings.Persian ? i.Fa : i.En;
        }

        public static string City(string? iso, string? city)
        {
            var i = Of(iso);
            if (i != null && i.CityFa != "") return Strings.Persian ? i.CityFa : i.CityEn;
            return city ?? "";
        }
    }
}
