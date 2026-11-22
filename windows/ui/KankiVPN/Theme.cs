using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Media;

namespace KankiVPN
{
    /// <summary>One whole colour theme, field for field the Android app's Palette (Ui.kt).</summary>
    public sealed class Palette
    {
        public string Id = "";
        public Color Gold, GoldLight, GoldDeep, Shine, Ink, Text, Sub, BgTop, BgMid, BgBottom;
        public Color Surface, Surface2, Panel, OnGold, Accent, Connected;
        public Color ModeSmart, ModeGame, ModeAnti, ModeDns, Mix, Danger, Warn, UserCountry, ToggleOn;
        public Color GameAlt, Tracer, NavTop, NavBottom, OnNav;
        /// <summary>Colour of the gaming runners; null = the gaming colour.</summary>
        public Color? Runner;
        /// <summary>The second neon of the theme (Ui.kt gold2): gradients of lights and edges</summary>
        public Color Gold2;

        public static Palette Lerp(Palette a, Palette b, double f)
        {
            Color L(Color x, Color y) => Gfx.Lerp(x, y, f);
            return new Palette
            {
                Id = b.Id,
                Gold = L(a.Gold, b.Gold), GoldLight = L(a.GoldLight, b.GoldLight), GoldDeep = L(a.GoldDeep, b.GoldDeep), Shine = L(a.Shine, b.Shine),
                Ink = L(a.Ink, b.Ink), Text = L(a.Text, b.Text), Sub = L(a.Sub, b.Sub),
                BgTop = L(a.BgTop, b.BgTop), BgMid = L(a.BgMid, b.BgMid), BgBottom = L(a.BgBottom, b.BgBottom),
                Surface = L(a.Surface, b.Surface), Surface2 = L(a.Surface2, b.Surface2), Panel = L(a.Panel, b.Panel),
                OnGold = L(a.OnGold, b.OnGold), Accent = L(a.Accent, b.Accent), Connected = L(a.Connected, b.Connected),
                ModeSmart = L(a.ModeSmart, b.ModeSmart), ModeGame = L(a.ModeGame, b.ModeGame), ModeAnti = L(a.ModeAnti, b.ModeAnti),
                ModeDns = L(a.ModeDns, b.ModeDns), Mix = L(a.Mix, b.Mix), Danger = L(a.Danger, b.Danger), Warn = L(a.Warn, b.Warn),
                UserCountry = L(a.UserCountry, b.UserCountry), ToggleOn = L(a.ToggleOn, b.ToggleOn),
                GameAlt = L(a.GameAlt, b.GameAlt), Tracer = L(a.Tracer, b.Tracer),
                NavTop = L(a.NavTop, b.NavTop), NavBottom = L(a.NavBottom, b.NavBottom), OnNav = L(a.OnNav, b.OnNav),
                Runner = a.Runner is { } ra && b.Runner is { } rb ? L(ra, rb) : b.Runner,
                Gold2 = L(a.Gold2, b.Gold2),
            };
        }
    }

    /// <summary>The Android app's six glass themes (Ui.kt glassTheme, «Kanki Glass» 1.32+), same colours: each theme is one
    /// background and one neon colour (plus a second neon), and every other colour is made from them.
    /// Every themed colour in XAML is a DynamicResource of the keys below; the drawn parts (Gfx and the
    /// controls in Draw.cs) read <see cref="P"/> and repaint on <see cref="Changed"/>.</summary>
    public static class Theme
    {
        static Color H(uint argb) => Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

        /// <summary>Ui.kt glassTheme, line for line</summary>
        static Palette Glass(string id, uint bg, uint accent, uint accent2, uint ink = 0xFF0A0D0B, uint text = 0xFFF2F5F1, uint sub = 0xFFA9B3AB)
        {
            Color b = H(bg), a = H(accent), on = H(ink);
            Color hi = Gfx.Lerp(a, Colors.White, .40), deep = Gfx.Lerp(a, Colors.Black, .45);
            return new Palette
            {
                Id = id, Gold = a, GoldLight = hi, GoldDeep = deep, Shine = Gfx.Lerp(a, Colors.White, .75),
                Ink = Gfx.Lerp(b, Colors.Black, .55), Text = H(text), Sub = H(sub),
                BgTop = Gfx.Lerp(b, Colors.White, .035), BgMid = b, BgBottom = Gfx.Lerp(b, Colors.Black, .45),
                Surface = Gfx.A(Gfx.Lerp(b, Colors.White, .07), .90), Surface2 = Gfx.A(Gfx.Lerp(b, Colors.White, .11), .94),
                Panel = Gfx.A(Gfx.Lerp(b, Colors.Black, .30), .96), OnGold = on,
                Accent = hi, Connected = a,
                ModeSmart = a, ModeGame = a, ModeAnti = a, ModeDns = a, Mix = a,
                Danger = H(0xFFFF6C65), Warn = H(0xFFFFC24A), UserCountry = hi, ToggleOn = a,
                GameAlt = hi, Tracer = Colors.White,
                NavTop = hi, NavBottom = a, OnNav = on,
                Gold2 = H(accent2),
            };
        }

        static readonly Palette[] Themes =
        {
            Glass("kanki", 0xFF141414, 0xFF2BEE34, 0xFF19E6C1),
            Glass("cyber", 0xFF3C1A47, 0xFFB6FF00, 0xFFB44CFF),
            Glass("tiffany", 0xFF171717, 0xFF21F1A8, 0xFF4FACFE),
            Glass("vulcan", 0xFF001621, 0xFFFF4103, 0xFFFF2A6D),
            Glass("malt", 0xFF2A2312, 0xFFFFBE0B, 0xFFFF8C00),
            Glass("cyprus", 0xFF004741, 0xFFF0EDE4, 0xFFB8C4C2, ink: 0xFF062A27, sub: 0xFFA6C2BD),
        };

        /// <summary>The theme ids and their names in Settings (Ui.kt ThemeNames)</summary>
        public static readonly (string Id, string Name)[] Names =
        {
            ("kanki", "Kanki Default"), ("cyber", "Cyber Gaming"), ("tiffany", "Tiffany Pro"),
            ("vulcan", "Vulcanico"), ("malt", "Gold Malt"), ("cyprus", "Cyprus Sand"),
        };

        public static string Current { get; private set; } = "kanki";

        /// <summary>The colours in use right now (during a theme change, part way between the two).</summary>
        public static Palette P { get; private set; } = Themes[0];

        /// <summary>Raised whenever <see cref="P"/> changes (every frame of a theme change).</summary>
        public static event Action? Changed;

        static Palette? from;
        static double fade = 1;

        /// <summary>Switches theme; <paramref name="animate"/>: in 420 ms like the Android app, else at once.</summary>
        public static void Apply(string name, bool animate = false)
        {
            var t = Themes.FirstOrDefault(x => x.Id == name) ?? Themes[0];
            Current = t.Id;
            if (animate && P.Id != t.Id && !Clock.Frozen)
            {
                from = P;
                fade = 0;
                Clock.Tick -= Step;
                Clock.Tick += Step;
                return;
            }
            Set(t);
        }

        static void Step(double dt)
        {
            var target = Themes.First(x => x.Id == Current);
            fade = Math.Min(1, fade + dt / .42);
            if (fade >= 1 || from == null)
            {
                Clock.Tick -= Step;
                Set(target);
                return;
            }
            Set(Palette.Lerp(from, target, Gfx.FastOutSlowIn(fade)));
        }

        static void Set(Palette p)
        {
            P = p;
            var r = Application.Current.Resources;
            void Put(string k, Color c)
            {
                r["C" + k] = c;
                var b = new SolidColorBrush(c);
                b.Freeze();
                r[k] = b;
            }
            // the names the XAML has always used, and the Android names
            Put("Primary", p.Gold); Put("PrimaryLight", p.GoldLight); Put("PrimaryDark", p.GoldDeep); Put("PrimaryPale", p.Shine);
            Put("Bg", p.Ink); Put("Text", p.Text); Put("Muted", p.Sub);
            Put("Card", p.Surface); Put("Sheet", p.Surface2); Put("Surface", p.Panel); Put("OnPrimary", p.OnGold);
            Put("Edge", Gfx.A(p.GoldDeep, .30)); Put("EdgeStrong", Gfx.A(p.Gold, .70));
            Put("Connected", p.Connected); Put("Accent", p.Accent);
            Put("ModeSmart", p.ModeSmart); Put("ModeGame", p.ModeGame); Put("ModeAnti", p.ModeAnti);
            Put("Danger", p.Danger); Put("Warn", p.Warn); Put("User", p.UserCountry); Put("ToggleOn", p.ToggleOn);
            Put("NavTop", p.NavTop); Put("NavBottom", p.NavBottom); Put("OnNav", p.OnNav);
            Put("AccentSoft", Gfx.A(p.Accent, .12)); Put("PrimarySoft", Gfx.A(p.Gold, .12)); Put("PrimaryFaint", Gfx.A(p.Gold, .18));
            Put("WhiteFaint", Gfx.A(Colors.White, .08)); Put("WhiteTrack", Color.FromArgb(0x1F, 255, 255, 255));
            Put("PrimaryEdge", Gfx.A(p.Gold, .6)); Put("SwitchOff", Color.FromArgb(0x33, 255, 255, 255)); Put("SubRing", Gfx.A(p.Sub, .4));
            Put("Divider", Gfx.A(p.Gold, .10)); Put("PrimaryField", Gfx.A(p.Gold, .35)); Put("SubFaint", Gfx.A(p.Sub, .45)); Put("Gold55", Gfx.A(p.Gold, .55));
            var avatar = new LinearGradientBrush(Gfx.A(p.Gold, .30), Gfx.A(p.Gold, .10), 90);
            avatar.Freeze();
            r["AvatarFill"] = avatar; Put("PrimaryLine", Gfx.A(p.Gold, .3));
            var bar = new LinearGradientBrush(Gfx.A(p.Gold, .65), p.Gold, 0);
            bar.Freeze();
            r["GoldBar"] = bar; Put("PanelSoft", Gfx.A(p.Panel, .75)); Put("PrimaryHair", Gfx.A(p.Gold, .12)); Put("ConnectedSoft", Gfx.A(p.Connected, .10)); Put("ConnectedEdge", Gfx.A(p.Connected, .5));
            var div = new LinearGradientBrush(new GradientStopCollection
            {
                new(Colors.Transparent, 0), new(Gfx.A(p.Gold, .45), .5), new(Colors.Transparent, 1),
            }, 90);
            div.Freeze();
            r["StatDivider"] = div;
            var nav = new LinearGradientBrush(p.NavTop, p.NavBottom, 90);
            nav.Freeze();
            r["NavSelected"] = nav;
            var glow = new RadialGradientBrush(Gfx.A(p.Gold, .33), Colors.Transparent);
            glow.Freeze();
            r["Glow"] = glow;
            Changed?.Invoke();
        }

        /// <summary>A theme's own colours (for its card in Settings).</summary>
        public static Palette Of(string name) => Themes.FirstOrDefault(x => x.Id == name) ?? Themes[0];

        public static Color Col(string key) => (Color)Application.Current.Resources["C" + key];

        /// <summary>The three dots on a theme card in Settings, as on Android: light neon, second neon, neon.</summary>
        public static Color[] Dots(string name)
        {
            var t = Of(name);
            return new[] { t.GoldLight, t.Gold2, t.Gold };
        }
    }
}
