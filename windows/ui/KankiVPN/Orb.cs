using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Effects;
using static KankiVPN.Gfx;

namespace KankiVPN
{
    /// <summary>
    /// HomeUi.kt OrbButton, drawn the same way: slowly turning rays of light, a warm halo, single stars that circle
    /// and twinkle, a neon ring with two comets, a fine tick ring turning the other way, and the button itself (black
    /// glass, thick bright gold edge, logo, title and one line). Connecting: everything turns fast and a white light
    /// runs round the edge; connected: waves go out of the button. Gaming: the light and rings take the gaming
    /// colour and two tiny neon figures run on the ring, one running away and one chasing with a gun; connected, it
    /// fires (faster with more data, faster bullets with lower ping) and every new ping sample sends a burst under the
    /// runner's feet, who jumps over it. Drawn at the design size (138) and scaled as a whole to <see cref="Diameter"/>.
    /// </summary>
    public sealed class Orb : Grid
    {
        public const double Base = 138, Footprint = 1.7;

        readonly StackPanel middle = new() { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        readonly Logo3D logo = new() { Width = 30, Height = 30 };
        readonly TextBlock title = new() { HorizontalAlignment = HorizontalAlignment.Center, FontWeight = FontWeights.Black, TextWrapping = TextWrapping.NoWrap };
        readonly TextBlock sub = new() { HorizontalAlignment = HorizontalAlignment.Center, FontWeight = FontWeights.Bold, TextWrapping = TextWrapping.NoWrap };
        readonly ScaleTransform zoom = new();
        readonly DropShadowEffect titleGlow = new() { ShadowDepth = 0, BlurRadius = 6, Opacity = .75 };

        public Orb()
        {
            FlowDirection = FlowDirection.LeftToRight;
            Background = Brushes.Transparent;
            Cursor = System.Windows.Input.Cursors.Hand;
            middle.Width = Base * .74;
            middle.LayoutTransform = zoom;
            logo.HorizontalAlignment = HorizontalAlignment.Center;
            middle.Children.Add(logo);
            title.Margin = new Thickness(0, 3, 0, 0);
            title.Effect = titleGlow;
            middle.Children.Add(title);
            middle.Children.Add(sub);
            Children.Add(middle);
            Diameter = Base;
            Loaded += (_, _) =>
            {
                Theme.Changed += InvalidateVisual;
                if (!Clock.Frozen) Clock.Tick += OnTick;
            };
            Unloaded += (_, _) =>
            {
                Theme.Changed -= InvalidateVisual;
                Clock.Tick -= OnTick;
            };
        }

        double diameter;

        /// <summary>The button's diameter (the space it takes is 1.7 times that: ring and runners).</summary>
        public double Diameter
        {
            get => diameter;
            set
            {
                diameter = value;
                Width = Height = value * Footprint;
                zoom.ScaleX = zoom.ScaleY = value / Base;
                InvalidateVisual();
            }
        }

        // ---- state from the window
        string state = "off";
        bool game;
        long? ping;
        List<long> hist = new();
        long bytes;

        /// <summary>off, connecting or on.</summary>
        public string State
        {
            get => state;
            set
            {
                state = value;
                Retarget();
            }
        }

        public bool Game
        {
            get => game;
            set
            {
                game = value;
                Retarget();
            }
        }

        /// <summary>Live ping, the last ping samples, and bytes a second (down + up).</summary>
        public void Live(long? livePing, IReadOnlyList<long> history, long bytesPerSec)
        {
            ping = livePing;
            bytes = bytesPerSec;
            var h = history.ToList();
            var fresh = h.Count != hist.Count || (h.Count > 0 && hist.Count > 0 && h[^1] != hist[^1]);
            hist = h;
            calm = PingBase(hist, ping);
            if (!fresh || hist.Count == 0) return;
            if (hist.Count >= 2)
            {
                var k = Math.Min(1, Math.Abs(hist[^1] - hist[^2]) / 20.0);
                if (k >= .05)
                {
                    kickFrom = Math.Max(Kick, k);
                    kickT = 0;
                }
            }
            if (game && state == "on") { chase.Burst = 3; chase.JumpAt = time + BulletFlight(hist.Count > 0 ? hist[^1] : ping) - .07; }
        }

        /// <summary>Title (connect, connected…) and the line under it; number: the line is the connected time.</summary>
        public void Text(string head, string line, bool number)
        {
            title.Text = head;
            sub.Text = line;
            var off = state == "off";
            title.FontSize = off ? 22 : 16;
            sub.FontSize = number ? 14 : 11;
            sub.FontFamily = number ? (FontFamily)Application.Current.Resources["Poppins"] : (FontFamily)Application.Current.Resources["Vazir"];
            sub.FlowDirection = number ? FlowDirection.LeftToRight : (Strings.Persian ? FlowDirection.RightToLeft : FlowDirection.LeftToRight);
            title.FlowDirection = Strings.Persian ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            Recolor();
        }

        void Recolor()
        {
            var p = Theme.P;
            var on = state == "on";
            var tc = on ? p.Connected : Lerp(p.Gold, GameLight, gameA.Value);
            title.Foreground = Solid(tc);
            titleGlow.Color = tc;
            var num = sub.FontFamily == (FontFamily)Application.Current.Resources["Poppins"];
            sub.Foreground = Solid(num ? White : A(p.Text, .85));
            logo.Animate = on;
        }

        // ---- animated values (Compose animateFloatAsState with tween)
        readonly Tween speed = new(.6), gameA = new(0), gCon = new(0), gOn = new(0), lit = new(.6), onA = new(0), conA = new(0);
        double kickFrom, kickT = 1, blastT = 1, calm = .3;
        double spin, tick, time, beat;
        readonly Chase chase = new();

        double Kick => kickT >= 1 ? 0 : kickFrom * (1 - LinearOutSlowIn(kickT));

        void Retarget()
        {
            var on = state == "on";
            var con = state == "connecting";
            var wasGameOn = gOn.Target > .5;
            speed.To(con ? 4.5 : on ? 1.3 : .6, .9);
            gameA.To(game ? 1 : 0, .5);
            gCon.To(game && con ? 1 : 0, .35);
            gOn.To(game && on ? 1 : 0, .7);
            lit.To(on ? 1 : con ? .85 : .6, .7);
            onA.To(on ? 1 : 0, .7);
            conA.To(con ? 1 : 0, .4);
            if (game && on && !wasGameOn) blastT = 0;
            if (Clock.Frozen) Snapshot();
            Recolor();
            InvalidateVisual();
        }

        /// <summary>Screenshots: the picture at the clock's fixed time.</summary>
        void Snapshot()
        {
            time = Clock.Time;
            spin = tick = time * speed.Value;
            beat = time / BeatPeriod(ping);
            blastT = 1;
            var run = gCon.Value > .5 ? 1.7 : gOn.Value > .5 ? .9 : .32;
            chase.Reset();
            for (var i = 0; i < 120; i++) chase.Step(1 / 60.0, time - 2 + i / 60.0, run, 0, gOn.Value > .5, FireRate(bytes), BulletFlight(ping));
        }

        void OnTick(double dt)
        {
            if (!IsVisible) return;
            foreach (var t in new[] { speed, gameA, gCon, gOn, lit, onA, conA }) t.Step(dt);
            if (kickT < 1) kickT = Math.Min(1, kickT + dt / 3);
            if (blastT < 1) blastT = Math.Min(1, blastT + dt / 1.4);
            spin += dt * speed.Value;
            tick += dt * speed.Value;
            time += dt;
            beat += dt / BeatPeriod(ping);
            var run = gCon.Value > .5 ? 1.7 : gOn.Value > .5 ? .9 + .7 * Kick : .32;
            chase.Step(dt, time, run, Kick, gOn.Value > .5, FireRate(bytes), BulletFlight(hist.Count > 0 ? hist[^1] : ping));
            if (gameA.Moving) Recolor();
            InvalidateVisual();
        }

        // ---- the colours of gaming
        static Color GameC => Theme.P.ModeGame;
        static Color GameLight => Lerp(Lerp(GameC, Theme.P.GameAlt, .3), White, .15);
        static Color Runner => Theme.P.Runner ?? GameC;
        static Color RunnerLight => Theme.P.Runner is { } r ? Lerp(r, White, .12) : GameLight;
        static Color GameCyan => Theme.P.GameAlt;
        static Color[] GameRgb => new[] { GameC, GameCyan, GameC, GameLight, GameC };
        static Color Tracer => Theme.P.Tracer;
        static Color TracerHot => Lerp(Tracer, White, .5);

        static Color RgbAt(double f)
        {
            var p = GameRgb;
            var x = (((f % 1) + 1) % 1) * (p.Length - 1);
            var i = Math.Min((int)x, p.Length - 2);
            return Lerp(p[i], p[i + 1], x - i);
        }

        // ---- the rhythm of the ping
        public static double BeatPeriod(long? ping) => ping == null || ping < 0 ? 1.15 : Math.Clamp(1.25 - ping.Value / 400.0, .55, 1.15);

        static double BeatEnv(double u)
        {
            double Bump(double c, double w) { var x = Math.Clamp(1 - Math.Abs(u - c) / w, 0, 1); return x * x; }
            return Math.Max(Bump(.05, .06), .6 * Bump(.22, .07));
        }

        static double PingBase(List<long> hist, long? ping)
        {
            var t = hist.Skip(Math.Max(0, hist.Count - 6)).ToList();
            var jit = t.Count < 2 ? 0 : t.Zip(t.Skip(1), (a, b) => (double)Math.Abs(a - b)).Average();
            var lvl = Math.Min(Math.Max((ping ?? 0) - 40, 0) / 300.0, .2);
            return .3 + Math.Min(jit / 30, .3) + lvl;
        }

        static double FireRate(long bps) => .5 + 3.5 * Math.Clamp(Math.Log10(1 + bps / 8000.0) / 2.5, 0, 1);
        static double BulletFlight(long? ping) => Math.Clamp(.28 + (ping ?? 60) / 280.0, .3, 1.1);

        public static Color PingColor(long ms) => ms < 0 ? Theme.P.Danger : ms < 90 ? Theme.P.Connected : ms < 200 ? Theme.P.Warn : Theme.P.Danger;

        static double Hash(int a, int b)
        {
            unchecked
            {
                var x = a * 374761393 + b * 668265263;
                x = (x ^ (int)((uint)x >> 13)) * 1274126177;
                return ((x ^ (int)((uint)x >> 16)) & 0xFFFFFF) / 16777215.0;
            }
        }

        // ---- stars
        sealed record Star(double Orbit, double Start, double Size, double Twinkle, bool IsWhite);

        static readonly Star[] stars = MakeStars();

        static Star[] MakeStars()
        {
            var rnd = new JavaRandom(2077);
            var s = new Star[26];
            for (var i = 0; i < s.Length; i++)
            {
                var orbit = 1.55 + rnd.NextFloat() * rnd.NextFloat() * .55;
                var start = rnd.NextFloat() * 360.0;
                var size = .5 + rnd.NextFloat() * rnd.NextFloat() * 1.3;
                var tw = .8 + rnd.NextFloat() * 2.0;
                s[i] = new Star(orbit, start, size, tw, rnd.NextFloat() < .5);
            }
            return s;
        }

        static readonly (double m, double a)[] NeonLayers = { (4.5, .07), (3.0, .15), (1.9, .32), (1.0, 1) };

        static void NeonRing(DrawingContext dc, Color color, Point c, double r, double w, double strength)
        {
            foreach (var (m, a) in NeonLayers) Ring(dc, A(color, a * strength), c, r, w * m);
            Ring(dc, A(White, .55 * strength), c, r, w * .35);
        }

        static void NeonComet(DrawingContext dc, Color color, double head, double length, Point c, double r, double w, double alpha)
        {
            const int seg = 16;
            for (var i = 0; i < seg; i++)
            {
                var f = (i + 1.0) / seg;
                var start = head - length + length * i / seg;
                foreach (var (m, a) in NeonLayers) Arc(dc, Pen(A(color, a * f * alpha), w * m), c, r, start, length / seg + .6);
            }
            var rad = head * Math.PI / 180;
            var p = new Point(c.X + r * Math.Cos(rad), c.Y + r * Math.Sin(rad));
            foreach (var (m, a) in NeonLayers) Circle(dc, A(color, a * alpha), p, w * m * .7);
            Circle(dc, A(White, alpha), p, w * .5);
        }

        /// <summary>A ring coloured round its length like Compose's sweepGradient (from 3 o'clock, clockwise).</summary>
        static void SweepRing(DrawingContext dc, Point c, double r, double w, double alpha, double rot)
        {
            const int n = 72;
            for (var i = 0; i < n; i++)
            {
                var col = RgbAt((i + .5) / n);
                Arc(dc, Pen(A(col, alpha), w), c, r, rot + i * 360.0 / n, 360.0 / n + .4);
            }
        }

        static void RgbRing(DrawingContext dc, Point c, double r, double w, double a, double rot)
        {
            foreach (var (m, al) in new[] { (4.5, .07), (3.0, .15), (1.9, .32) }) SweepRing(dc, c, r, w * m, al * a, rot);
            SweepRing(dc, c, r, w, a, rot);
            Ring(dc, A(White, .4 * a), c, r, w * .35);
        }

        static readonly Brush noBrush = Brushes.Transparent;

        protected override void OnRender(DrawingContext dc)
        {
            var size = RenderSize.Width;
            dc.DrawRectangle(noBrush, null, new Rect(RenderSize));
            var p = Theme.P;
            var scale = diameter / Base;
            dc.PushTransform(new TranslateTransform(size / 2, size / 2));
            dc.PushTransform(new ScaleTransform(scale, scale));
            var c = new Point(0, 0);
            const double r = Base / 2;
            var gold = p.Gold;
            var breathe = .5 + .5 * Math.Sin(time * 2.1);
            var deg = spin * 180 / Math.PI;
            var u = beat % 1;
            var env = BeatEnv(u);
            double ga = gameA.Value, gc = gCon.Value, go = gOn.Value, li = lit.Value, oa = onA.Value, ca = conA.Value;
            var amp = Math.Clamp(calm + .8 * Kick, .25, 1.3);
            var rayC = Lerp(gold, GameC, .75 * ga);
            var warm = Lerp(p.Gold, GameC, .8 * ga);
            var pump = 1 + .7 * env * amp * go;

            // ---- rays of light that turn slowly
            var rayA = Math.Clamp((.10 + .08 * li) * (.7 + .3 * breathe) * pump, 0, 1);
            Geometry Ray(double len) => Poly(true, Pt(c.X - 1.5, c.Y), Pt(c.X - r * .13, c.Y - len), Pt(c.X + r * .13, c.Y - len), Pt(c.X + 1.5, c.Y));
            Brush RayBrush(double len) => Linear(Pt(0, c.Y - len), Pt(0, c.Y), (0, A(rayC, 0)), (.45, rayC), (1, A(rayC, 0)));
            var shortRay = Ray(r * 1.5);
            var longRay = Ray(r * 1.78);
            var bShort = RayBrush(r * 1.5);
            var bLong = RayBrush(r * 1.78);
            dc.PushOpacity(rayA);
            for (var i = 0; i < 16; i++)
            {
                dc.PushTransform(new RotateTransform(deg * .12 + i * 22.5, c.X, c.Y));
                dc.DrawGeometry(i % 2 == 1 ? bShort : bLong, null, i % 2 == 1 ? shortRay : longRay);
                dc.Pop();
            }
            dc.Pop();
            // ---- warm halo behind the button
            Circle(dc, Radial(c, r * 2, (0, A(warm, 0)),
                (.45, A(warm, Math.Min((.30 + .2 * li) * (.75 + .25 * breathe) * pump, 1))),
                (.72, A(warm, .07 * pump)), (1, Clear)), c, r * 2);
            // ---- single stars
            foreach (var s in stars)
            {
                var rad = (s.Start + deg * .07 / s.Orbit) * Math.PI / 180;
                var o = Pt(c.X + r * s.Orbit * Math.Cos(rad), c.Y + r * s.Orbit * Math.Sin(rad) * .8);
                var tw = .5 + .5 * Math.Sin(time * s.Twinkle + s.Start);
                var col = s.IsWhite ? White : Lerp(p.GoldLight, GameLight, ga);
                var a = .35 + .65 * tw;
                Circle(dc, A(col, .16 * a), o, s.Size * 4);
                Circle(dc, A(col, a), o, s.Size * .8);
                if (s.Size > 1.1)
                {
                    var l = s.Size * 6;
                    Line(dc, A(col, .7 * a), Pt(o.X - l, o.Y), Pt(o.X + l, o.Y), .7);
                    Line(dc, A(col, .7 * a), Pt(o.X, o.Y - l), Pt(o.X, o.Y + l), .7);
                }
            }
            // ---- outer neon ring with two comets (gaming: the gaming neon, no comets, the runners' ground)
            var r2 = r * 1.42;
            var goldA = 1 - ga;
            if (goldA > .01) NeonRing(dc, gold, c, r2, 1.2, (.45 + .25 * li) * goldA);
            if (ga > .01) RgbRing(dc, c, r2, 1.2, ga, -deg * .6);
            if (goldA > .01)
            {
                NeonComet(dc, p.GoldLight, deg, 80, c, r2, 1.6, goldA);
                NeonComet(dc, p.GoldLight, deg + 180, 80, c, r2, 1.6, goldA);
            }
            // ---- fine tick ring turning the other way
            dc.PushTransform(new RotateTransform(-tick * 180 / Math.PI * .35, c.X, c.Y));
            var r1 = r * 1.2;
            for (var i = 0; i < 72; i++)
            {
                var an = i * 5.0 * Math.PI / 180;
                var major = i % 6 == 0;
                var hh = major ? 5.0 : 3.0;
                double ca2 = Math.Cos(an), sa = Math.Sin(an);
                var bc = major ? p.GoldLight : gold;
                var tc = ga > .01 ? Lerp(bc, RgbAt(i / 72.0), .85 * ga) : bc;
                Line(dc, A(tc, major ? .85 : .4), Pt(c.X + (r1 - hh) * ca2, c.Y + (r1 - hh) * sa), Pt(c.X + (r1 + hh) * ca2, c.Y + (r1 + hh) * sa), major ? 2 : 1.1);
            }
            dc.Pop();
            if (ga > .01) chase.Draw(dc, c, r2, time, ga, go);

            // ---- the button: black glass with a thick bright gold edge (gaming: dark glass of the gaming colour, turning neon edge)
            var glowA = (.35 + .25 * li * breathe) * (1 + .4 * env * amp * go);
            Circle(dc, Radial(c, r * 1.32, A(Lerp(p.Gold, GameC, .6 * ga), Math.Min(glowA, 1)), Clear), c, r * 1.32);
            Circle(dc, Lerp(p.Ink, p.GoldDeep, .06), c, r + 4);
            var glass = new[]
            {
                Lerp(Lerp(p.Ink, p.GoldDeep, .24), Lerp(p.Ink, GameC, .2), ga),
                Lerp(Lerp(p.Ink, p.GoldDeep, .08), Lerp(p.Ink, GameC, .08), ga),
                p.Ink,
            };
            Circle(dc, Radial(Pt(c.X, c.Y - r * .3), r * 1.1, glass), c, r);
            if (goldA > .01)
            {
                dc.PushOpacity(goldA);
                Ring(dc, Vertical(c.Y - r, c.Y + r, p.Shine, gold, p.GoldDeep, p.GoldLight), c, r + 1, 5);
                dc.Pop();
            }
            if (ga > .01)
            {
                var glowUp = 1 + env * go * (.6 + .5 * amp);
                var rot = deg * 1.6;
                SweepRing(dc, c, r + 1, 15, Math.Min(.16 * glowUp * ga, 1), rot);
                SweepRing(dc, c, r + 1, 9, Math.Min(.32 * glowUp * ga, 1), rot);
                SweepRing(dc, c, r + 1, 5, ga, rot);
                Ring(dc, A(White, .45 * ga), c, r + 1, 1);
            }
            Ring(dc, A(Lerp(p.GoldLight, GameCyan, ga), .5), c, r - 6, 1);
            dc.DrawEllipse(Vertical(c.Y - r, c.Y - r * .1, A(White, .20), Clear), null, Pt(c.X, c.Y - r * .5), r * .7, r * .4);
            // connecting: a white light runs round the edge
            if (ca > .01) Arc(dc, Pen(A(Lerp(p.Shine, GameLight, gc), ca), 5, true), c, r + 1, time * 344, 69);
            // connected: dashed edge and two waves of the theme's second colour going out
            if (oa > .01 && goldA > .01)
            {
                var pen = new Pen(Solid(A(p.Accent, .55 * oa * goldA)), 2)
                {
                    DashStyle = new DashStyle(new[] { r * .75 / 2, r * .25 / 2 }, (time * r) / 2),
                    DashCap = PenLineCap.Flat,
                };
                pen.Freeze();
                dc.DrawGeometry(null, pen, CircleFromRight(c, r + 1));
            }
            if (oa > .01)
                for (var k = 0; k < 2; k++)
                {
                    var q = (time / 2.6 + k * .5) % 1;
                    Ring(dc, A(p.Accent, (1 - q) * .55 * oa * (1 - go)), c, r * (1.05 + q * .75), 1.6);
                }
            if (go > .01)
                foreach (var (w, al) in new[] { (12.0, .07), (6.0, .16), (2.6, .45) })
                    Ring(dc, A(GameC, Math.Min(al * env * (.6 + .5 * amp) * go, 1)), c, r + 2, w);
            if (game && blastT < 1)
            {
                var q = 1 - blastT;
                Circle(dc, Radial(c, r * 1.6, A(GameLight, .5 * q * q), Clear), c, r * 1.6);
            }
            dc.Pop();
            dc.Pop();
        }

        /// <summary>A circle path that starts at 3 o'clock and goes clockwise, like Skia's (for the dashes).</summary>
        static Geometry CircleFromRight(Point c, double r)
        {
            var g = new StreamGeometry();
            using (var x = g.Open())
            {
                x.BeginFigure(Pt(c.X + r, c.Y), false, true);
                x.ArcTo(Pt(c.X - r, c.Y), new Size(r, r), 0, false, SweepDirection.Clockwise, true, true);
                x.ArcTo(Pt(c.X + r, c.Y), new Size(r, r), 0, false, SweepDirection.Clockwise, true, true);
            }
            g.Freeze();
            return g;
        }

        // ================================================================ the gaming chase

        /// <summary>HomeUi.kt Chase: where the two runners are on the ring, their steps, the bullets on the way and the runner's jump.</summary>
        sealed class Chase
        {
            public sealed record Shot(double T0, double A0, double A1, double Dur);

            public double Pos, Speed = .3, Stride, Gap = .8, LastShot = -9, JumpAt = -9;
            public int Burst;
            double fireAcc, burstAcc;
            int count;
            public readonly List<Shot> Shots = new();
            const double Tau = Math.PI * 2;

            public void Reset()
            {
                Pos = 0; Speed = .3; Stride = 0; Gap = .8; LastShot = -9; JumpAt = -9; Burst = 0;
                fireAcc = burstAcc = 0; count = 0; Shots.Clear();
            }

            public void Step(double dt, double time, double target, double closer, bool firing, double rate, double flight)
            {
                Speed += (target - Speed) * Math.Min(1, dt * 2.5);
                Pos = (Pos + dt * Speed) % Tau;
                Stride += dt * (Speed * 14 + 4);
                Gap += (.8 + .08 * Math.Sin(time * .6) - .22 * closer - Gap) * Math.Min(1, dt * 2);
                if (firing)
                {
                    fireAcc += dt * rate;
                    if (fireAcc >= 1) { fireAcc -= 1; Fire(time, flight, false); }
                    if (Burst > 0)
                    {
                        burstAcc += dt;
                        if (burstAcc >= .09) { burstAcc = 0; Burst--; Fire(time, flight, true); }
                    }
                }
                else
                {
                    fireAcc = 0;
                    Burst = 0;
                }
                Shots.RemoveAll(s => time - s.T0 > s.Dur + .3);
            }

            void Fire(double time, double flight, bool aimed)
            {
                var miss = aimed ? 0 : .06 + .1 * Hash(count++, 3);
                Shots.Add(new Shot(time, Pos, Pos + Gap + Speed * flight - miss, flight));
                LastShot = time;
            }

            static Vector Up(double th) => new(Math.Sin(th), -Math.Cos(th));
            static Vector Fwd(double th) => new(Math.Cos(th), Math.Sin(th));

            /// <summary>HomeUi.kt stickman: one neon line figure on the ring; returns the gun's muzzle.</summary>
            static Point Stickman(DrawingContext dc, Point c, double rr, double th, double h, double step, double run, double lift, bool gun, double recoil, double a)
            {
                var up = Up(th);
                var fw = Fwd(th);
                var bas = c + up * rr;
                Point W(Vector q) => bas + fw * (q.X * h) + up * (q.Y * h);
                static Vector Dn(double ang) => new(Math.Sin(ang), -Math.Cos(ang));
                var jump = Math.Clamp(lift / (.55 * h), 0, 1);
                var lean = .08 + .22 * run;
                var spine = new Vector(Math.Sin(lean), Math.Cos(lean));
                var hip = new Vector(0, .43 + .035 * run * Math.Cos(2 * step) + lift / h);
                var neck = hip + spine * .3;
                var head = neck + spine * .17 + new Vector(.02, 0);
                var shoulder = hip + spine * .27;
                var body = new StreamGeometry();
                var gunPath = new StreamGeometry();
                var muzzle = new Point();
                using (var x = body.Open())
                {
                    void Seg(Vector p0, Vector p1, Vector? p2 = null)
                    {
                        x.BeginFigure(W(p0), false, false);
                        x.LineTo(W(p1), true, true);
                        if (p2 is { } q) x.LineTo(W(q), true, true);
                    }
                    Seg(hip, neck);
                    for (var k = 0; k <= 1; k++)
                    {
                        var ph = step + k * Math.PI;
                        var thigh = (.25 + .45 * run) * Math.Sin(ph) + .5 * jump;
                        var bend = .15 + (.25 + run) * Math.Max(0, Math.Cos(ph)) + 1.1 * jump;
                        var knee = hip + Dn(thigh) * .23;
                        Seg(hip, knee, knee + Dn(thigh - bend) * .23);
                    }
                    if (gun)
                    {
                        var grip = shoulder + new Vector(.2 - .06 * recoil, -.04);
                        var fore = grip + new Vector(.13, .01);
                        Seg(shoulder, shoulder + new Vector(.06, -.11), grip);
                        Seg(shoulder, shoulder + new Vector(.12, -.08), fore);
                        var stock = grip + new Vector(-.11, .02);
                        var tip = grip + new Vector(.36, .035);
                        muzzle = W(tip);
                        var mag = grip + new Vector(.06, 0);
                        using var g = gunPath.Open();
                        g.BeginFigure(W(stock), false, false);
                        g.LineTo(muzzle, true, true);
                        g.BeginFigure(W(mag), false, false);
                        g.LineTo(W(mag + new Vector(.025, -.09)), true, true);
                    }
                    else
                    {
                        for (var k = 0; k <= 1; k++)
                        {
                            var q = step + k * Math.PI;
                            var upper = -(.2 + .6 * run) * Math.Sin(q) - .4 * jump;
                            var elbow = shoulder + Dn(upper) * .19;
                            Seg(shoulder, elbow, elbow + Dn(upper + .5 + run) * .17);
                        }
                    }
                }
                var hc = W(head);
                var hr = .13 * h;
                dc.DrawGeometry(null, Pen(A(Runner, .16 * a), 4, true), body);
                Circle(dc, A(Runner, .2 * a), hc, hr + 2);
                dc.DrawGeometry(null, Pen(A(RunnerLight, a), 1.7, true), body);
                dc.DrawGeometry(null, Pen(A(White, .5 * a), .6, true), body);
                Circle(dc, A(RunnerLight, a), hc, hr);
                Circle(dc, A(White, .55 * a), hc, hr * .5);
                if (gun)
                {
                    dc.DrawGeometry(null, Pen(A(White, .22 * a), 3.4, true), gunPath);
                    dc.DrawGeometry(null, Pen(A(Lerp(Theme.P.Sub, White, .7), a), 1.7, true), gunPath);
                }
                return muzzle;
            }

            /// <summary>HomeUi.kt chaseScene: bullets and their sparks, the runner (jumping) and the shooter, the muzzle flash.</summary>
            public void Draw(DrawingContext dc, Point c, double ring, double time, double a, double firing)
            {
                const double h = 18;
                var rr = ring + 1;
                var run = Math.Clamp((Speed - .3) / .6, 0, 1);
                var muzzleAng = .62 * h / rr;
                var gunH = .74 * h;
                foreach (var s in Shots)
                {
                    var t = time - s.T0;
                    var span = s.A1 - s.A0 - muzzleAng;
                    if (t <= s.Dur)
                    {
                        var p = t / s.Dur;
                        var tailP = Math.Max(0, p - (12 / rr) / Math.Max(.05, span));
                        Point At(double q) => c + Up(s.A0 + muzzleAng + span * q) * (rr + gunH * (1 - q * q));
                        var hd = At(p);
                        var tl = At(tailP);
                        Line(dc, A(Tracer, .35 * a), tl, hd, 3.4);
                        Line(dc, A(TracerHot, a), tl, hd, 1.4);
                        Circle(dc, A(White, a), hd, 1.1);
                    }
                    else
                    {
                        var k = Math.Clamp((t - s.Dur) / .3, 0, 1);
                        var hit = c + Up(s.A1) * rr;
                        var up = Up(s.A1);
                        var back = Fwd(s.A1) * -1;
                        Circle(dc, A(Tracer, .5 * (1 - k) * a), hit, 1.5 + 3 * k);
                        for (var i = 0; i < 5; i++)
                        {
                            var ang = -.9 + .45 * i;
                            var d = up * Math.Cos(ang) + back * Math.Sin(ang);
                            var len = (2 + 6 * k) * (.6 + .4 * Hash(i, BitConverter.SingleToInt32Bits((float)s.T0)));
                            Line(dc, A(TracerHot, (1 - k) * a), hit + d * (len * .4), hit + d * len, 1);
                        }
                    }
                }
                var jt = time - JumpAt;
                var lift = jt is >= 0 and <= .5 ? .55 * h * Math.Sin(jt / .5 * Math.PI) : 0;
                Stickman(dc, c, rr, Pos + Gap, h, Stride + 1.3, run, lift, false, 0, a);
                var since = time - LastShot;
                var recoil = firing > .5 ? Math.Exp(-since * 22) : 0;
                var muzzle = Stickman(dc, c, rr, Pos, h, Stride, run, 0, true, recoil, a);
                if (firing > .5 && since < .08)
                {
                    var f = 1 - since / .08;
                    var fw = Fwd(Pos);
                    var up = Up(Pos);
                    Circle(dc, A(Tracer, .45 * f * a), muzzle, 4 * f);
                    foreach (var d in new[] { fw, fw * .8 + up * .5, fw * .8 - up * .5 })
                        Line(dc, A(TracerHot, f * a), muzzle, muzzle + d * (5 * f), 1.1);
                    Circle(dc, A(White, f * a), muzzle, 1.2);
                }
            }
        }
    }
}
