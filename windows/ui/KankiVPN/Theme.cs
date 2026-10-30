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
            };
        }
    }

    /// <summary>The Android app's two themes (Ui.kt Themes: "yellow" and "crimson"), same colours.
    /// Every themed colour in XAML is a DynamicResource of the keys below; the drawn parts (Gfx and the
    /// controls in Draw.cs) read <see cref="P"/> and repaint on <see cref="Changed"/>.</summary>
    public static class Theme
    {
        static Color H(uint argb) => Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);

        static readonly Palette[] Themes =
        {
            new()
            {
                Id = "yellow", Gold = H(0xFFFFD60A), GoldLight = H(0xFFFFE45C), GoldDeep = H(0xFFB38F00), Shine = H(0xFFFFF4B8),
                Ink = H(0xFF000000), Text = H(0xFFFFFBEA), Sub = H(0xFFA39E8A),
                BgTop = H(0xFF000000), BgMid = H(0xFF000000), BgBottom = H(0xFF000000),
                Surface = H(0xE6080704), Surface2 = H(0xF00F0D07), Panel = H(0xFF090804), OnGold = H(0xFF111111),
                Accent = H(0xFF2EE06F), Connected = H(0xFF3BE67C),
                ModeSmart = H(0xFFFFD60A), ModeGame = H(0xFFFFB21F), ModeAnti = H(0xFF2EE06F), ModeDns = H(0xFFD9935B),
                Mix = H(0xFFFFB21F), Danger = H(0xFFFF3B30), Warn = H(0xFFFFAA2B), UserCountry = H(0xFF2EE06F), ToggleOn = H(0xFFFFD60A),
                GameAlt = H(0xFFFFD60A), Tracer = H(0xFFFFF4B8),
                NavTop = H(0xFFFFE45C), NavBottom = H(0xFFFFD60A), OnNav = H(0xFF111111),
            },
            new()
            {
                Id = "crimson", Gold = H(0xFFE8203F), GoldLight = H(0xFFFF5C74), GoldDeep = H(0xFF9E0E28), Shine = H(0xFFFF7A8E),
                Ink = H(0xFF000000), Text = H(0xFFFFF5F6), Sub = H(0xFFA8949A),
                BgTop = H(0xFF000000), BgMid = H(0xFF000000), BgBottom = H(0xFF000000),
                Surface = H(0xE6070203), Surface2 = H(0xF00E0406), Panel = H(0xFF070203), OnGold = H(0xFFFFFFFF),
                Accent = H(0xFFF2C879), Connected = H(0xFFF2C879),
                ModeSmart = H(0xFFE8203F), ModeGame = H(0xFFF2C879), ModeAnti = H(0xFFFF6B8E), ModeDns = H(0xFFB565D9),
                Mix = H(0xFFF2C879), Danger = H(0xFFFF4D4D), Warn = H(0xFFF5B342), UserCountry = H(0xFFF2C879), ToggleOn = H(0xFFE8203F),
                GameAlt = H(0xFFFF5C74), Tracer = H(0xFFFFE08A),
                NavTop = H(0xFFC0152F), NavBottom = H(0xFF6E0918), OnNav = H(0xFFFFFFFF),
            },
        };

        public static string Current { get; private set; } = "yellow";

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
            Put("Divider", Gfx.A(p.Gold, .10)); Put("Gold55", Gfx.A(p.Gold, .55));
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

        /// <summary>The three dots on a theme card in Settings, as on Android.</summary>
        public static Color[] Dots(string name) => name == "crimson"
            ? new[] { H(0xFFF2C879), H(0xFFFF5C74), H(0xFFE8203F) }
            : new[] { H(0xFF2EE06F), H(0xFF3BE67C), H(0xFFFFD60A) };
    }
}
