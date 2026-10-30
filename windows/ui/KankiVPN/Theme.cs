using System.Collections.Generic;
using System.Windows;
using System.Windows.Media;

namespace KankiVPN
{
    /// <summary>The Android app's two themes (Ui.kt Themes: "yellow" and "crimson"), same colours.
    /// Every themed colour in XAML is a DynamicResource of these keys.</summary>
    public static class Theme
    {
        static readonly Dictionary<string, Dictionary<string, string>> Palettes = new()
        {
            ["yellow"] = new()
            {
                ["Primary"] = "#FFD60A", ["PrimaryLight"] = "#FFE45C", ["PrimaryDark"] = "#B38F00", ["PrimaryPale"] = "#FFF4B8",
                ["Bg"] = "#000000", ["Text"] = "#FFFBEA", ["Muted"] = "#A39E8A",
                ["Card"] = "#E6080704", ["Sheet"] = "#F00F0D07", ["Surface"] = "#090804", ["OnPrimary"] = "#111111",
                ["Edge"] = "#4DB38F00", ["EdgeStrong"] = "#B3FFD60A",
                ["Connected"] = "#3BE67C", ["ModeSmart"] = "#FFD60A", ["ModeGame"] = "#FFB21F", ["ModeAnti"] = "#2EE06F",
                ["Danger"] = "#FF3B30", ["Warn"] = "#FFAA2B", ["User"] = "#2EE06F",
                ["NavTop"] = "#FFE45C", ["NavBottom"] = "#FFD60A", ["OnNav"] = "#111111",
            },
            ["crimson"] = new()
            {
                ["Primary"] = "#E8203F", ["PrimaryLight"] = "#FF5C74", ["PrimaryDark"] = "#9E0E28", ["PrimaryPale"] = "#FF7A8E",
                ["Bg"] = "#000000", ["Text"] = "#FFF5F6", ["Muted"] = "#A8949A",
                ["Card"] = "#E6070203", ["Sheet"] = "#F00E0406", ["Surface"] = "#070203", ["OnPrimary"] = "#FFFFFF",
                ["Edge"] = "#4D9E0E28", ["EdgeStrong"] = "#B3E8203F",
                ["Connected"] = "#F2C879", ["ModeSmart"] = "#E8203F", ["ModeGame"] = "#F2C879", ["ModeAnti"] = "#FF6B8E",
                ["Danger"] = "#FF4D4D", ["Warn"] = "#F5B342", ["User"] = "#F2C879",
                ["NavTop"] = "#C0152F", ["NavBottom"] = "#6E0918", ["OnNav"] = "#FFFFFF",
            },
        };

        public static string Current { get; private set; } = "yellow";

        public static void Apply(string name)
        {
            if (!Palettes.ContainsKey(name)) name = "yellow";
            Current = name;
            var r = Application.Current.Resources;
            foreach (var (k, v) in Palettes[name])
            {
                var c = (Color)ColorConverter.ConvertFromString(v);
                r["C" + k] = c;
                var b = new SolidColorBrush(c);
                b.Freeze();
                r[k] = b;
            }
            var nav = new LinearGradientBrush(Col("NavTop"), Col("NavBottom"), 90);
            nav.Freeze();
            r["NavSelected"] = nav;
            var glow = new RadialGradientBrush(Color.FromArgb(0x55, Col("Primary").R, Col("Primary").G, Col("Primary").B), Colors.Transparent);
            glow.Freeze();
            r["Glow"] = glow;
        }

        public static Color Col(string key) => (Color)ColorConverter.ConvertFromString(Palettes[Current][key]);

        /// <summary>The three dots on a theme card in Settings, as on Android.</summary>
        public static Color[] Dots(string name) => name == "crimson"
            ? new[] { Hex("#F2C879"), Hex("#FF5C74"), Hex("#E8203F") }
            : new[] { Hex("#2EE06F"), Hex("#3BE67C"), Hex("#FFD60A") };

        static Color Hex(string s) => (Color)ColorConverter.ConvertFromString(s);
    }
}
