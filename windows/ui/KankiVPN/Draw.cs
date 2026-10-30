using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using static KankiVPN.Gfx;

namespace KankiVPN
{
    /// <summary>A drawn part that repaints when the theme changes, and every frame while <see cref="Live"/>.</summary>
    public abstract class Drawn : FrameworkElement
    {
        bool live, hooked;

        protected Drawn()
        {
            Loaded += (_, _) =>
            {
                Theme.Changed += Repaint;
                hooked = true;
                Hook();
            };
            Unloaded += (_, _) =>
            {
                Theme.Changed -= Repaint;
                hooked = false;
                Hook();
            };
        }

        void Repaint() => InvalidateVisual();

        /// <summary>Animated: <see cref="Frame"/> runs every frame (only while shown).</summary>
        protected bool Live
        {
            get => live;
            set
            {
                if (live == value) return;
                live = value;
                Hook();
            }
        }

        bool ticking;

        void Hook()
        {
            var want = live && hooked && !Clock.Frozen;
            if (want == ticking) return;
            ticking = want;
            if (want) Clock.Tick += OnTick;
            else Clock.Tick -= OnTick;
        }

        void OnTick(double dt)
        {
            if (!IsVisible) return;
            Frame(dt);
            InvalidateVisual();
        }

        protected virtual void Frame(double dt) { }

        protected static Palette P => Theme.P;
    }

    /// <summary>A value that eases to its target (Compose animateFloatAsState with tween; FastOutSlowIn).</summary>
    public sealed class Tween
    {
        double from, t = 1, dur;
        public double Value { get; private set; }
        public double Target { get; private set; }
        readonly Func<double, double> ease;

        public Tween(double v, Func<double, double>? ease = null)
        {
            Value = Target = from = v;
            this.ease = ease ?? FastOutSlowIn;
        }

        public void To(double target, double seconds)
        {
            if (target == Target) return;
            from = Value;
            Target = target;
            dur = seconds;
            t = 0;
            if (Clock.Frozen || seconds <= 0) Snap(target);
        }

        public void Snap(double v)
        {
            Value = Target = from = v;
            t = 1;
        }

        public void Step(double dt)
        {
            if (t >= 1) return;
            t = Math.Min(1, t + dt / Math.Max(dur, 1e-3));
            Value = from + (Target - from) * ease(t);
        }

        public bool Moving => t < 1;
    }

    // ==================================================================== glass

    /// <summary>
    /// The Android glass (Ui.kt goldGlass, ModesUi.kt modeGlass, the selected bottom tab): dark glass with a
    /// soft outer glow, a diagonal tint, a little shine at the top and a bright edge. A Border, so Padding,
    /// CornerRadius and Child work as usual; Background and BorderBrush are not used.
    /// </summary>
    public class Glass : Border
    {
        public enum Kinds { Card, Mode, Nav, Plain, Gold, Hero, Line }

        public static readonly DependencyProperty KindProperty = DependencyProperty.Register(nameof(Kind), typeof(Kinds), typeof(Glass),
            new FrameworkPropertyMetadata(Kinds.Card, FrameworkPropertyMetadataOptions.AffectsRender));
        public static readonly DependencyProperty SelectedProperty = DependencyProperty.Register(nameof(Selected), typeof(bool), typeof(Glass),
            new FrameworkPropertyMetadata(false, FrameworkPropertyMetadataOptions.AffectsRender, (d, e) => ((Glass)d).OnSelected()));
        /// <summary>Theme colour name of the tint (Primary, Connected, ModeSmart, ModeGame, ModeAnti, Danger, Accent).</summary>
        public static readonly DependencyProperty TintProperty = DependencyProperty.Register(nameof(Tint), typeof(string), typeof(Glass),
            new FrameworkPropertyMetadata("Primary", FrameworkPropertyMetadataOptions.AffectsRender));

        public Kinds Kind { get => (Kinds)GetValue(KindProperty); set => SetValue(KindProperty, value); }
        public bool Selected { get => (bool)GetValue(SelectedProperty); set => SetValue(SelectedProperty, value); }
        public string Tint { get => (string)GetValue(TintProperty); set => SetValue(TintProperty, value); }

        readonly Tween lit = new(0);

        public Glass()
        {
            CornerRadius = new CornerRadius(16);
            Loaded += (_, _) => { Theme.Changed += InvalidateVisual; };
            Unloaded += (_, _) => { Theme.Changed -= InvalidateVisual; };
        }

        bool stepping;

        void OnSelected()
        {
            lit.To(Selected ? 1 : 0, .26);
            if (!lit.Moving || stepping) return;
            stepping = true;
            Clock.Tick += StepLit;
        }

        void StepLit(double dt)
        {
            lit.Step(dt);
            InvalidateVisual();
            if (lit.Moving) return;
            Clock.Tick -= StepLit;
            stepping = false;
        }

        public static Color TintOf(string name) => name switch
        {
            "Connected" => Theme.P.Connected,
            "ModeSmart" => Theme.P.ModeSmart,
            "ModeGame" => Theme.P.ModeGame,
            "ModeAnti" => Theme.P.ModeAnti,
            "Danger" => Theme.P.Danger,
            "Accent" => Theme.P.Accent,
            "Warn" => Theme.P.Warn,
            _ => Theme.P.Gold,
        };

        static Geometry Round(Rect r, double rad)
        {
            var g = new RectangleGeometry(r, Math.Max(0, rad), Math.Max(0, rad));
            g.Freeze();
            return g;
        }

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var h = RenderSize.Height;
            if (w <= 0 || h <= 0) return;
            var p = Theme.P;
            var rad = CornerRadius.TopLeft;
            var box = new Rect(0, 0, w, h);
            var shape = Round(box, rad);
            var c = TintOf(Tint);
            var l = Kind == Kinds.Mode ? lit.Value : Selected ? 1 : 0;
            switch (Kind)
            {
                case Kinds.Nav:
                    foreach (var (sw, a) in new[] { (10.0, .08), (5.0, .16) }) dc.DrawGeometry(null, Pen(A(p.Gold, a), sw), shape);
                    dc.PushClip(shape);
                    dc.DrawRectangle(Vertical(0, h, p.NavTop, p.NavBottom), null, box);
                    dc.Pop();
                    dc.DrawGeometry(null, Pen(Vertical(0, h, A(White, .45), A(p.GoldLight, .25)), 1), Round(new Rect(.5, .5, w - 1, h - 1), rad - .5));
                    return;
                case Kinds.Mode:
                    if (l > .01)
                        foreach (var (sw, a) in new[] { (11.0, .06), (6.5, .12), (3.2, .26) }) dc.DrawGeometry(null, Pen(A(c, a * l), sw), shape);
                    dc.PushClip(shape);
                    dc.DrawRectangle(Solid(p.Surface), null, box);
                    dc.DrawRectangle(Diagonal(box, A(c, .05 + .17 * l), A(White, .015), A(Black, .32 - .08 * l), A(c, .03 + .08 * l)), null, box);
                    dc.DrawRectangle(Vertical(0, h * .5, A(White, .07 + .07 * l), Clear), null, box);
                    var line = Horizontal(w * .2, w * .8, Clear, Lerp(c, White, .25), Clear);
                    var y = h - 1;
                    dc.PushOpacity(.3 * l);
                    dc.DrawLine(Pen(line, 3), new Point(w * .2, y), new Point(w * .8, y));
                    dc.Pop();
                    dc.PushOpacity(.25 + .75 * l);
                    dc.DrawLine(Pen(line, 1.1), new Point(w * .2, y), new Point(w * .8, y));
                    dc.Pop();
                    dc.Pop();
                    var bw = 1 + .5 * l;
                    dc.DrawGeometry(null, Pen(Diagonal(box, A(Lerp(c, White, .3 * l), .30 + .70 * l), A(c, .22 + .68 * l), A(c, .30 + .70 * l)), bw),
                        Round(new Rect(bw / 2, bw / 2, w - bw, h - bw), rad - bw / 2));
                    return;
                case Kinds.Gold:
                    // the big button glows; the small one (radius 12) does not
                    if (rad > 13)
                        foreach (var (sw, a) in new[] { (9.0, .08), (5.0, .16) }) dc.DrawGeometry(null, Pen(A(p.Gold, a), sw), shape);
                    dc.PushClip(shape);
                    dc.DrawRectangle(Vertical(0, h, p.Shine, p.Gold, p.GoldDeep), null, box);
                    dc.Pop();
                    if (rad > 13) dc.DrawGeometry(null, Pen(A(p.GoldLight, .7), 1), Round(new Rect(.5, .5, w - 1, h - 1), rad - .5));
                    return;
                case Kinds.Line:
                    // big (LineButton): dark with a 1.5 edge; small (SmallLine, radius 12): tinted with a thin edge
                    var big = rad > 13;
                    dc.PushClip(shape);
                    dc.DrawRectangle(Solid(big ? A(p.Panel, .8) : A(c, .08)), null, box);
                    dc.Pop();
                    var lw = big ? 1.5 : 1;
                    dc.DrawGeometry(null, Pen(A(c, big ? .8 : .6), lw), Round(new Rect(lw / 2, lw / 2, w - lw, h - lw), rad - lw / 2));
                    return;
                case Kinds.Hero:
                    dc.PushClip(shape);
                    dc.DrawRectangle(Diagonal(box, A(p.Gold, .20), A(p.Gold, .04), A(p.Accent, .08)), null, box);
                    dc.Pop();
                    dc.DrawGeometry(null, Pen(Diagonal(box, A(p.Gold, .7), A(p.Gold, .12), A(p.Accent, .45)), 1), Round(new Rect(.5, .5, w - 1, h - 1), rad - .5));
                    return;
                case Kinds.Plain:
                    dc.PushClip(shape);
                    dc.DrawRectangle(Solid(p.Surface), null, box);
                    dc.Pop();
                    return;
                default:
                    var sel = Selected;
                    foreach (var (sw, a) in sel ? new[] { (12.0, .06), (7.0, .12), (3.5, .24) } : new[] { (8.0, .03), (3.0, .06) })
                        dc.DrawGeometry(null, Pen(A(c, a), sw), shape);
                    dc.PushClip(shape);
                    dc.DrawRectangle(Solid(p.Surface), null, box);
                    dc.DrawRectangle(sel
                        ? Diagonal(box, A(c, .20), A(c, .05), A(Black, .25))
                        : Diagonal(box, A(White, .06), A(White, .01), A(Black, .25)), null, box);
                    dc.DrawRectangle(Vertical(0, h * .45, A(White, sel ? .10 : .05), Clear), null, box);
                    dc.Pop();
                    var t = sel ? 1.5 : 1;
                    dc.DrawGeometry(null, Pen(sel
                            ? Diagonal(box, p.Shine, c, A(c, .45), c)
                            : Diagonal(box, A(c, .85), A(c, .15), A(White, .08), A(c, .45)), t),
                        Round(new Rect(t / 2, t / 2, w - t, h - t), rad - t / 2));
                    return;
            }
        }
    }

    // ==================================================================== background

    /// <summary>Ui.kt KankiBackground and Twinkles: pure black, a faint glow of the accent behind the
    /// connect button and 34 twinkling stars (12 when calm).</summary>
    public sealed class Backdrop : Drawn
    {
        sealed record Mote(double X, double Y, double R, double Phase, double Speed, bool Warm);

        static readonly Mote[] motes = Make();

        static Mote[] Make()
        {
            var rnd = new JavaRandom(77);
            var m = new Mote[34];
            for (var i = 0; i < m.Length; i++)
            {
                var x = .03 + rnd.NextFloat() * .94;
                var y = .02 + rnd.NextFloat() * .96;
                var r = .5 + rnd.NextFloat() * 1.1;
                var ph = rnd.NextFloat();
                var sp = 1 + rnd.NextInt(3);
                m[i] = new Mote(x, y, r, ph, sp, rnd.NextFloat() < .6);
            }
            return m;
        }

        public Backdrop()
        {
            Live = true;
            IsHitTestVisible = false;
        }

        /// <summary>Theme colour name of the glow (Android: the mode colour when connected, else the theme colour).</summary>
        public string Accent { get; set; } = "Primary";

        public bool Calm { get; set; }

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var h = RenderSize.Height;
            var p = P;
            dc.DrawRectangle(Solid(p.Ink), null, new Rect(0, 0, w, h));
            dc.DrawRectangle(Vertical(0, h, p.BgTop, p.BgMid, p.BgBottom), null, new Rect(0, 0, w, h));
            var ac = Glass.TintOf(Accent);
            var c = new Point(w / 2, h * .52);
            Circle(dc, Radial(c, w * .62, A(ac, .09), A(ac, .02), Clear), c, w * .62);
            var twinkle = Clock.Time / 9;
            var n = Calm ? 12 : motes.Length;
            for (var i = 0; i < n; i++)
            {
                var m = motes[i];
                var tw = (Math.Sin((twinkle * m.Speed + m.Phase) * 6.2832) + 1) / 2;
                var pos = new Point(m.X * w, m.Y * h);
                var col = m.Warm ? p.GoldLight : p.Accent;
                var a = .85 * tw * tw;
                Circle(dc, A(col, a * .18), pos, m.R * 5);
                Circle(dc, A(col, a), pos, m.R);
                if (m.R > 1.1)
                {
                    var l = m.R * 7 * (.6 + .4 * tw);
                    Line(dc, A(col, a * .7), new Point(pos.X - l, pos.Y), new Point(pos.X + l, pos.Y), .6);
                    Line(dc, A(col, a * .7), new Point(pos.X, pos.Y - l), new Point(pos.X, pos.Y + l), .6);
                }
            }
        }
    }

    // ==================================================================== logo

    /// <summary>Ui.kt Logo3D: the gold shield with a raised edge, dark inside and a gold K; Animate: a slow band of light.</summary>
    public sealed class Logo3D : Drawn
    {
        public static readonly DependencyProperty AnimateProperty = DependencyProperty.Register(nameof(Animate), typeof(bool), typeof(Logo3D),
            new FrameworkPropertyMetadata(false, (d, e) => ((Logo3D)d).Live = (bool)e.NewValue));

        public bool Animate { get => (bool)GetValue(AnimateProperty); set => SetValue(AnimateProperty, value); }

        /// <summary>Gold glow behind the shield (0..1).</summary>
        public double Glow { get; set; }

        static Geometry Shield(double w, double h, double dx = 0, double dy = 0, double inset = 0)
        {
            double l = w * (.13 + inset), r = w * (.87 - inset), tp = h * (.05 + inset * 1.1);
            var g = new StreamGeometry();
            using (var x = g.Open())
            {
                x.BeginFigure(new Point(w * .5 + dx, tp + dy), true, true);
                x.LineTo(new Point(r + dx, h * (.15 + inset * .6) + dy), true, true);
                x.LineTo(new Point(r + dx, h * .50 + dy), true, true);
                x.QuadraticBezierTo(new Point(r + dx, h * (.79 - inset * .4) + dy), new Point(w * .5 + dx, h * (.96 - inset * 1.4) + dy), true, true);
                x.QuadraticBezierTo(new Point(l + dx, h * (.79 - inset * .4) + dy), new Point(l + dx, h * .50 + dy), true, true);
                x.LineTo(new Point(l + dx, h * (.15 + inset * .6) + dy), true, true);
            }
            g.Freeze();
            return g;
        }

        static Geometry KShape(double w, double h, double dx = 0, double dy = 0) => Poly(true,
            Pt(w * .35 + dx, h * .29 + dy), Pt(w * .45 + dx, h * .29 + dy), Pt(w * .45 + dx, h * .45 + dy),
            Pt(w * .58 + dx, h * .29 + dy), Pt(w * .70 + dx, h * .29 + dy), Pt(w * .53 + dx, h * .48 + dy),
            Pt(w * .71 + dx, h * .69 + dy), Pt(w * .59 + dx, h * .69 + dy), Pt(w * .45 + dx, h * .53 + dy),
            Pt(w * .45 + dx, h * .69 + dy), Pt(w * .35 + dx, h * .69 + dy));

        public static void Paint(DrawingContext dc, double w, double h, double sweep, double glow)
        {
            var p = Theme.P;
            if (glow > 0)
            {
                var c = new Point(w / 2, h / 2);
                Circle(dc, Radial(c, w * .8, A(p.Gold, .32 * glow), Clear), c, w * .8);
            }
            dc.DrawGeometry(Solid(A(Black, .45)), null, Shield(w, h, w * .012, h * .02));
            var face = Shield(w, h);
            dc.DrawGeometry(Linear(new Point(0, 0), new Point(w, h), p.GoldLight, p.Gold, p.GoldDeep), null, face);
            var inner = Shield(w, h, inset: .075);
            dc.DrawGeometry(Vertical(0, h, A(p.Surface2, 1), p.Ink), null, inner);
            dc.DrawGeometry(null, Pen(A(p.GoldDeep, .95), w * .014, roundJoin: true), inner);
            dc.DrawGeometry(Solid(A(Black, .55)), null, KShape(w, h, w * .01, h * .012));
            dc.DrawGeometry(Vertical(0, h, p.GoldLight, p.Gold, p.GoldDeep), null, KShape(w, h));
            dc.PushClip(face);
            dc.DrawRectangle(Vertical(0, h * .22, A(White, .22), Clear), null, new Rect(0, 0, w, h * .22));
            if (sweep > -.4 && sweep < 1.4)
            {
                var x = sweep * w;
                dc.DrawRectangle(Linear(new Point(x - w * .22, 0), new Point(x + w * .22, h * .5), Clear, A(White, .36), Clear), null, new Rect(0, 0, w, h));
            }
            dc.Pop();
        }

        protected override void OnRender(DrawingContext dc)
        {
            var sw = Animate ? Clock.Time / 5.2 % 1 * 2.4 - .4 : -1;
            Paint(dc, RenderSize.Width, RenderSize.Height, sw, Glow);
        }
    }

    // ==================================================================== icons

    /// <summary>The Android app's drawn icons (Ui.kt navIcon / miniIcon, HomeUi.kt linkIcon, ModesUi.kt modeLogo).</summary>
    public sealed class KIcon : Drawn
    {
        public static readonly DependencyProperty KindProperty = DependencyProperty.Register(nameof(Kind), typeof(string), typeof(KIcon),
            new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender));
        public static readonly DependencyProperty FillProperty = DependencyProperty.Register(nameof(Fill), typeof(Brush), typeof(KIcon),
            new FrameworkPropertyMetadata(Brushes.White, FrameworkPropertyMetadataOptions.AffectsRender));

        public string Kind { get => (string)GetValue(KindProperty); set => SetValue(KindProperty, value); }
        public Brush Fill { get => (Brush)GetValue(FillProperty); set => SetValue(FillProperty, value); }

        public KIcon() => FlowDirection = FlowDirection.LeftToRight;

        protected override void OnRender(DrawingContext dc)
        {
            var c = Fill is SolidColorBrush s ? s.Color : Colors.White;
            Paint(dc, Kind, c, RenderSize.Width);
        }

        static Pen St(Color c, double sw) => Pen(c, sw, true, true);

        public static void Paint(DrawingContext dc, string kind, Color c, double w)
        {
            var b = Solid(c);
            var cen = new Point(w / 2, w / 2);
            switch (kind)
            {
                case "home":
                {
                    var sw = w * .075;
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w * .12, w * .48), false, false); x.LineTo(Pt(w * .5, w * .14), true, true); x.LineTo(Pt(w * .88, w * .48), true, true);
                        x.BeginFigure(Pt(w * .22, w * .40), false, false); x.LineTo(Pt(w * .22, w * .86), true, true); x.LineTo(Pt(w * .78, w * .86), true, true); x.LineTo(Pt(w * .78, w * .40), true, true);
                        x.BeginFigure(Pt(w * .41, w * .86), false, false); x.LineTo(Pt(w * .41, w * .62), true, true); x.LineTo(Pt(w * .59, w * .62), true, true); x.LineTo(Pt(w * .59, w * .86), true, true);
                    }
                    dc.DrawGeometry(null, St(c, sw), g);
                    break;
                }
                case "globe":
                {
                    var sw = w * .075;
                    dc.DrawEllipse(null, St(c, sw), cen, w * .38, w * .38);
                    dc.DrawEllipse(null, Pen(c, sw * .85), cen, w * .18, w * .38);
                    dc.DrawLine(Pen(c, sw * .85), Pt(w * .13, w * .5), Pt(w * .87, w * .5));
                    dc.DrawLine(Pen(c, sw * .7), Pt(w * .20, w * .32), Pt(w * .80, w * .32));
                    dc.DrawLine(Pen(c, sw * .7), Pt(w * .20, w * .68), Pt(w * .80, w * .68));
                    break;
                }
                case "gauge":
                {
                    var sw = w * .075;
                    dc.DrawGeometry(null, St(c, sw), Arc(RectOf(w * .10, w * .16, w * .80, w * .80), 160, 220));
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .5, w * .56), Pt(w * .68, w * .36));
                    Circle(dc, b, Pt(w * .5, w * .56), w * .06);
                    break;
                }
                case "sliders":
                {
                    var sw = w * .075;
                    foreach (var (y, k) in new[] { (.26, .64), (.5, .34), (.74, .58) })
                    {
                        dc.DrawLine(Pen(c, sw, true), Pt(w * .12, w * y), Pt(w * .88, w * y));
                        Circle(dc, Black, Pt(w * k, w * y), w * .11);
                        dc.DrawEllipse(null, St(c, sw), Pt(w * k, w * y), w * .11, w * .11);
                    }
                    break;
                }
                case "link":
                {
                    var st = Pen(c, w * .095, true);
                    dc.PushTransform(new RotateTransform(-45, w / 2, w / 2));
                    dc.DrawRoundedRectangle(null, st, RectOf(w * .06, w * .36, w * .48, w * .28), w * .14, w * .14);
                    dc.DrawRoundedRectangle(null, st, RectOf(w * .46, w * .36, w * .48, w * .28), w * .14, w * .14);
                    dc.Pop();
                    break;
                }
                case "spark":
                {
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w * .5, w * .04), true, true);
                        x.QuadraticBezierTo(Pt(w * .56, w * .44), Pt(w * .96, w * .5), true, true);
                        x.QuadraticBezierTo(Pt(w * .56, w * .56), Pt(w * .5, w * .96), true, true);
                        x.QuadraticBezierTo(Pt(w * .44, w * .56), Pt(w * .04, w * .5), true, true);
                        x.QuadraticBezierTo(Pt(w * .44, w * .44), Pt(w * .5, w * .04), true, true);
                    }
                    dc.DrawGeometry(b, null, g);
                    Circle(dc, b, Pt(w * .84, w * .16), w * .08);
                    break;
                }
                case "down":
                case "up":
                {
                    var sw = w * .11 * 1.2;
                    var up = kind == "up";
                    var y0 = up ? w * .88 : w * .12;
                    var y1 = up ? w * .14 : w * .86;
                    var d = up ? w * .30 : -w * .30;
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .5, y0), Pt(w * .5, y1));
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .5, y1), Pt(w * .2, y1 + d));
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .5, y1), Pt(w * .8, y1 + d));
                    break;
                }
                case "shield":
                {
                    var sw = w * .11;
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w * .5, w * .08), false, true); x.LineTo(Pt(w * .86, w * .22), true, true); x.LineTo(Pt(w * .86, w * .50), true, true);
                        x.QuadraticBezierTo(Pt(w * .86, w * .78), Pt(w * .5, w * .94), true, true);
                        x.QuadraticBezierTo(Pt(w * .14, w * .78), Pt(w * .14, w * .50), true, true); x.LineTo(Pt(w * .14, w * .22), true, true);
                    }
                    dc.DrawGeometry(null, Pen(c, sw, roundJoin: true), g);
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .36, w * .52), Pt(w * .47, w * .63));
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .47, w * .63), Pt(w * .66, w * .40));
                    break;
                }
                case "smart":
                {
                    Geometry Star(double cx, double cy, double r)
                    {
                        var g = new StreamGeometry();
                        using (var x = g.Open())
                        {
                            x.BeginFigure(Pt(cx, cy - r), true, true);
                            x.QuadraticBezierTo(Pt(cx + r * .16, cy - r * .16), Pt(cx + r, cy), true, true);
                            x.QuadraticBezierTo(Pt(cx + r * .16, cy + r * .16), Pt(cx, cy + r), true, true);
                            x.QuadraticBezierTo(Pt(cx - r * .16, cy + r * .16), Pt(cx - r, cy), true, true);
                            x.QuadraticBezierTo(Pt(cx - r * .16, cy - r * .16), Pt(cx, cy - r), true, true);
                        }
                        return g;
                    }
                    dc.DrawGeometry(b, null, Star(w * .42, w * .42, w * .36));
                    dc.DrawGeometry(b, null, Star(w * .80, w * .80, w * .16));
                    break;
                }
                case "game":
                {
                    var sw = w * .085;
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w * .28, w * .28), false, true); x.LineTo(Pt(w * .72, w * .28), true, true);
                        x.BezierTo(Pt(w * .92, w * .28), Pt(w * .98, w * .62), Pt(w * .9, w * .76), true, true);
                        x.BezierTo(Pt(w * .84, w * .86), Pt(w * .72, w * .82), Pt(w * .66, w * .68), true, true);
                        x.LineTo(Pt(w * .34, w * .68), true, true);
                        x.BezierTo(Pt(w * .28, w * .82), Pt(w * .16, w * .86), Pt(w * .1, w * .76), true, true);
                        x.BezierTo(Pt(w * .02, w * .62), Pt(w * .08, w * .28), Pt(w * .28, w * .28), true, true);
                    }
                    dc.DrawGeometry(null, St(c, sw), g);
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .3, w * .40), Pt(w * .3, w * .56));
                    dc.DrawLine(Pen(c, sw, true), Pt(w * .22, w * .48), Pt(w * .38, w * .48));
                    Circle(dc, b, Pt(w * .66, w * .42), w * .05);
                    Circle(dc, b, Pt(w * .76, w * .53), w * .05);
                    break;
                }
                case "anti":
                {
                    var sw = w * .085;
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w * .5, w * .05), false, true); x.LineTo(Pt(w * .88, w * .19), true, true); x.LineTo(Pt(w * .88, w * .48), true, true);
                        x.BezierTo(Pt(w * .88, w * .72), Pt(w * .72, w * .88), Pt(w * .5, w * .96), true, true);
                        x.BezierTo(Pt(w * .28, w * .88), Pt(w * .12, w * .72), Pt(w * .12, w * .48), true, true); x.LineTo(Pt(w * .12, w * .19), true, true);
                    }
                    dc.DrawGeometry(null, St(c, sw), g);
                    dc.PushTransform(new RotateTransform(45, w * .5, w * .5));
                    var cx = w * .5;
                    var rk = new StreamGeometry();
                    using (var x = rk.Open())
                    {
                        x.BeginFigure(Pt(cx, w * .25), true, true);
                        x.QuadraticBezierTo(Pt(cx + w * .10, w * .34), Pt(cx + w * .08, w * .58), true, true);
                        x.LineTo(Pt(cx - w * .08, w * .58), true, true);
                        x.QuadraticBezierTo(Pt(cx - w * .10, w * .34), Pt(cx, w * .25), true, true);
                    }
                    dc.DrawGeometry(b, null, rk);
                    dc.DrawGeometry(b, null, Poly(true, Pt(cx - w * .08, w * .48), Pt(cx - w * .15, w * .62), Pt(cx - w * .08, w * .58)));
                    dc.DrawGeometry(b, null, Poly(true, Pt(cx + w * .08, w * .48), Pt(cx + w * .15, w * .62), Pt(cx + w * .08, w * .58)));
                    dc.DrawLine(Pen(A(c, .7 * Alpha(c)), sw * .8, true), Pt(cx, w * .64), Pt(cx, w * .72));
                    Circle(dc, A(Black, .55), Pt(cx, w * .40), w * .035);
                    dc.Pop();
                    break;
                }
                case "ping":
                    for (var i = 0; i < 3; i++)
                    {
                        var hh = w * (.30 + i * .28);
                        dc.DrawRoundedRectangle(b, null, RectOf(w * (.10 + i * .30), w * .92 - hh, w * .2, hh), w * .06, w * .06);
                    }
                    break;
                case "clock":
                {
                    var sw = w * .11;
                    dc.DrawEllipse(null, Pen(c, sw), cen, w * .38, w * .38);
                    dc.DrawLine(Pen(c, sw, true), cen, Pt(w * .5, w * .28));
                    dc.DrawLine(Pen(c, sw, true), cen, Pt(w * .68, w * .58));
                    break;
                }
                case "check": // HomeUi.kt CheckMark (on): a filled circle with a dark tick
                {
                    Circle(dc, b, cen, w / 2);
                    var k = w * .55;
                    var o = (w - k) / 2;
                    var pen = Pen(Theme.P.Ink, k * .16, true);
                    dc.DrawLine(pen, Pt(o + k * .12, o + k * .52), Pt(o + k * .40, o + k * .80));
                    dc.DrawLine(pen, Pt(o + k * .40, o + k * .80), Pt(o + k * .90, o + k * .22));
                    break;
                }
                case "account": // Ui.kt accountIcon
                {
                    Circle(dc, b, Pt(w / 2, w * .32), w * .20);
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Pt(w / 2, w * .96), true, true);
                        x.LineTo(Pt(w * .14, w * .96), true, true);
                        x.ArcTo(Pt(w * .86, w * .96), new Size(w * .36, w * .36), 0, false, SweepDirection.Clockwise, true, true);
                    }
                    dc.DrawGeometry(b, null, g);
                    break;
                }
                case "dot":
                    Circle(dc, b, cen, w / 2);
                    break;
                default:
                    Circle(dc, A(c, .25), cen, w * .45);
                    Circle(dc, A(c, .5), cen, w * .32);
                    Circle(dc, c, cen, w * .20);
                    break;
            }
        }
    }

    /// <summary>Ui.kt SpeedGauge: a 250° dial with Speedtest's log scale (0..1000 Mbps), a glowing arc and needle.</summary>
    public sealed class SpeedGauge : Drawn
    {
        static readonly double[] ticks = { 0, 5, 10, 25, 50, 100, 250, 500, 1000 };

        public static double Fraction(double v)
        {
            var x = Math.Clamp(v, 0, 1000);
            for (var i = 0; i < ticks.Length - 1; i++)
                if (x <= ticks[i + 1]) return (i + (x - ticks[i]) / (ticks[i + 1] - ticks[i])) / (ticks.Length - 1);
            return 1;
        }

        readonly Tween anim = new(0);
        string tint = "Primary";

        public SpeedGauge() => FlowDirection = FlowDirection.LeftToRight;

        /// <summary>value in Mbps; tint: theme colour name of the arc and needle.</summary>
        public void Set(double value, string colour)
        {
            tint = colour;
            anim.To(Fraction(value), .3);
            Live = anim.Moving;
            InvalidateVisual();
        }

        protected override void Frame(double dt)
        {
            anim.Step(dt);
            if (!anim.Moving) Live = false;
        }

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var h = RenderSize.Height;
            var col = Glass.TintOf(tint);
            var c = new Point(w / 2, h * .55);
            var r = Math.Min(w / 2, h * .55) * .9;
            const double start = 145, sweep = 250, sw = 14;
            var f = anim.Value;
            Arc(dc, Pen(Color.FromArgb(0x22, 255, 255, 255), sw, true), c, r, start, sweep);
            if (f > .001)
                foreach (var (m, a) in new[] { (2.2, .15), (1.4, .35), (1.0, 1.0) })
                    Arc(dc, Pen(A(col, a), sw * m, true), c, r, start, sweep * f);
            var face = new Typeface((FontFamily)Application.Current.Resources["Poppins"], FontStyles.Normal, FontWeights.SemiBold, FontStretches.Normal);
            var dpi = VisualTreeHelper.GetDpi(this).PixelsPerDip;
            for (var i = 0; i < ticks.Length; i++)
            {
                var ang = (start + sweep * i / (ticks.Length - 1)) * Math.PI / 180;
                Point At(double rr) => new(c.X + rr * Math.Cos(ang), c.Y + rr * Math.Sin(ang));
                Line(dc, A(P.Gold, .8), At(r - sw * 1.3), At(r - sw * 2.1), 2);
                var ft = new System.Windows.Media.FormattedText(((int)ticks[i]).ToString(), System.Globalization.CultureInfo.InvariantCulture,
                    FlowDirection.LeftToRight, face, 11, Solid(P.Sub), dpi);
                var lp = At(r - sw * 3.4);
                dc.DrawText(ft, new Point(lp.X - ft.Width / 2, lp.Y - ft.Height / 2));
            }
            var na = (start + sweep * f) * Math.PI / 180;
            var tip = new Point(c.X + (r - sw * 1.2) * Math.Cos(na), c.Y + (r - sw * 1.2) * Math.Sin(na));
            foreach (var (m, a) in new[] { (3.0, .15), (1.8, .35), (1.0, 1.0) }) Line(dc, A(col, a), c, tip, 4 * m);
            Circle(dc, P.Panel, c, 12);
            Ring(dc, col, c, 12, 3);
        }
    }

    /// <summary>Ui.kt SettingIcon: a line icon (drawn on a 24 grid) in a rounded box tinted with its colour.</summary>
    public sealed class SettingIcon : Drawn
    {
        public static readonly DependencyProperty KindProperty = DependencyProperty.Register(nameof(Kind), typeof(string), typeof(SettingIcon),
            new FrameworkPropertyMetadata("", FrameworkPropertyMetadataOptions.AffectsRender));
        public static readonly DependencyProperty TintProperty = DependencyProperty.Register(nameof(Tint), typeof(string), typeof(SettingIcon),
            new FrameworkPropertyMetadata("Primary", FrameworkPropertyMetadataOptions.AffectsRender));

        public string Kind { get => (string)GetValue(KindProperty); set => SetValue(KindProperty, value); }
        public string Tint { get => (string)GetValue(TintProperty); set => SetValue(TintProperty, value); }

        public SettingIcon()
        {
            Width = Height = 36;
            FlowDirection = FlowDirection.LeftToRight;
        }

        protected override void OnRender(DrawingContext dc)
        {
            var s = RenderSize.Width;
            var t = Glass.TintOf(Tint);
            var box = new Rect(0, 0, s, s);
            dc.DrawRoundedRectangle(Vertical(0, s, A(t, .22), A(t, .07)), Pen(A(t, .45), 1), new Rect(.5, .5, s - 1, s - 1), 12, 12);
            var w = s * .58;
            dc.PushTransform(new TranslateTransform((s - w) / 2, (s - w) / 2));
            Paint(dc, Kind, t, w);
            dc.Pop();
        }

        /// <summary>Ui.kt settingIcon.</summary>
        public static void Paint(DrawingContext dc, string kind, Color c, double size)
        {
            var u = size / 24;
            var st = Pen(c, 1.9 * u, true, true);
            Point P(double x, double y) => new(x * u, y * u);
            Geometry Pl(bool closed, params double[] v)
            {
                var pts = new Point[v.Length / 2];
                for (var i = 0; i < pts.Length; i++) pts[i] = P(v[i * 2], v[i * 2 + 1]);
                return Poly(closed, pts);
            }
            var b = Solid(c);
            switch (kind)
            {
                case "dns":
                    foreach (var y in new[] { 3.5, 13.5 })
                    {
                        dc.DrawRoundedRectangle(null, st, new Rect(P(3, y), new Size(18 * u, 7 * u)), 2.6 * u, 2.6 * u);
                        Circle(dc, b, P(7.2, y + 3.5), 1.2 * u);
                        dc.DrawLine(st, P(11, y + 3.5), P(16.5, y + 3.5));
                    }
                    break;
                case "auto":
                    dc.DrawGeometry(null, st, Arc(new Rect(P(2.8, 2.8), new Size(18.4 * u, 18.4 * u)), 140, 270));
                    dc.DrawGeometry(b, null, Pl(true, 13.4, 5.6, 8.2, 12.8, 11.7, 12.8, 10.7, 18.4, 15.9, 11.2, 12.4, 11.2));
                    break;
                case "sound":
                    dc.DrawGeometry(null, st, Pl(true, 3.5, 9.5, 7.5, 9.5, 12, 5.5, 12, 18.5, 7.5, 14.5, 3.5, 14.5));
                    dc.DrawGeometry(null, st, Arc(new Rect(P(7.5, 7.5), new Size(9 * u, 9 * u)), -45, 90));
                    dc.DrawGeometry(null, st, Arc(new Rect(P(4.5, 4.5), new Size(15 * u, 15 * u)), -50, 100));
                    break;
                case "palette":
                {
                    Point Rim(double deg) { var r = deg * Math.PI / 180; return P(12 + 9.4 * Math.Cos(r), 12 + 9.4 * Math.Sin(r)); }
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(Rim(62), false, false);
                        for (var k = 1; k <= 65; k++) x.LineTo(Rim(62 + 5 * k), true, true);
                        x.LineTo(Rim(388), true, true);
                        x.QuadraticBezierTo(P(14.6, 14.4), Rim(62), true, true);
                    }
                    dc.DrawGeometry(null, st, g);
                    foreach (var (x, y) in new[] { (7.8, 10.2), (10.4, 6.9), (14.6, 6.9), (17.0, 10.2) }) Circle(dc, b, P(x, y), 1.25 * u);
                    break;
                }
                case "split":
                    dc.DrawLine(st, P(12, 21), P(12, 12.5));
                    dc.DrawGeometry(null, st, Pl(false, 12, 12.5, 6.5, 7));
                    dc.DrawGeometry(null, st, Pl(false, 12, 12.5, 17.5, 7));
                    dc.DrawGeometry(null, st, Pl(false, 6.5, 11, 6.5, 7, 10.5, 7));
                    dc.DrawGeometry(null, st, Pl(false, 17.5, 11, 17.5, 7, 13.5, 7));
                    break;
                case "kill":
                {
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(P(12, 2.5), false, true); x.LineTo(P(20, 5.5), true, true); x.LineTo(P(20, 11.5), true, true);
                        x.QuadraticBezierTo(P(20, 18), P(12, 21.5), true, true);
                        x.QuadraticBezierTo(P(4, 18), P(4, 11.5), true, true); x.LineTo(P(4, 5.5), true, true);
                    }
                    dc.DrawGeometry(null, st, g);
                    dc.DrawGeometry(null, st, Arc(new Rect(P(8.6, 9), new Size(6.8 * u, 6.8 * u)), -50, 280));
                    dc.DrawLine(st, P(12, 8.2), P(12, 12));
                    break;
                }
                case "cart":
                    dc.DrawGeometry(null, st, Pl(false, 2.5, 3.5, 5.2, 3.5, 7.4, 15, 18.6, 15, 20.6, 7, 5.9, 7));
                    Circle(dc, b, P(9.2, 19.5), 1.7 * u);
                    Circle(dc, b, P(16.8, 19.5), 1.7 * u);
                    break;
                case "support":
                {
                    dc.DrawGeometry(null, st, Arc(new Rect(P(4, 3.5), new Size(16 * u, 16 * u)), 180, 180));
                    dc.DrawRoundedRectangle(b, null, new Rect(P(2.8, 11), new Size(4.4 * u, 6.6 * u)), 2 * u, 2 * u);
                    dc.DrawRoundedRectangle(b, null, new Rect(P(16.8, 11), new Size(4.4 * u, 6.6 * u)), 2 * u, 2 * u);
                    var g = new StreamGeometry();
                    using (var x = g.Open())
                    {
                        x.BeginFigure(P(19, 17.6), false, false);
                        x.QuadraticBezierTo(P(19, 20.8), P(14.5, 20.8), true, true);
                    }
                    dc.DrawGeometry(null, st, g);
                    Circle(dc, b, P(13.2, 20.8), 1.3 * u);
                    break;
                }
                case "share":
                    foreach (var (x, y) in new[] { (18.0, 5.5), (6.0, 12.0), (18.0, 18.5) }) dc.DrawEllipse(null, st, P(x, y), 2.6 * u, 2.6 * u);
                    dc.DrawLine(st, P(8.3, 10.8), P(15.7, 6.7));
                    dc.DrawLine(st, P(8.3, 13.2), P(15.7, 17.3));
                    break;
                case "logout":
                    dc.DrawGeometry(null, st, Pl(false, 10, 3.5, 4.5, 3.5, 4.5, 20.5, 10, 20.5));
                    dc.DrawLine(st, P(9.5, 12), P(20, 12));
                    dc.DrawGeometry(null, st, Pl(false, 16, 8, 20, 12, 16, 16));
                    break;
                case "lang":
                    dc.DrawEllipse(null, st, P(12, 12), 9 * u, 9 * u);
                    dc.DrawEllipse(null, st, P(12, 12), 4 * u, 9 * u);
                    dc.DrawLine(st, P(3.5, 9), P(20.5, 9));
                    dc.DrawLine(st, P(3.5, 15), P(20.5, 15));
                    break;
                case "user": // a person (the account)
                    dc.DrawEllipse(null, st, P(12, 8), 3.8 * u, 3.8 * u);
                    dc.DrawGeometry(null, st, Arc(new Rect(P(4.5, 14), new Size(15 * u, 13 * u)), 180, 180));
                    break;
                case "log": // lines of a log
                    foreach (var (y, l) in new[] { (6.0, 17.0), (10.0, 13.0), (14.0, 16.0), (18.0, 10.0) }) dc.DrawLine(st, P(4, y), P(4 + l, y));
                    break;
                default:
                    dc.DrawEllipse(null, st, P(12, 12), 8 * u, 8 * u);
                    break;
            }
        }
    }

    /// <summary>Kit.kt Spinner: three quarters of a ring that turns once in 0.85 s.</summary>
    public sealed class Spinner : Drawn
    {
        public Spinner() => Live = true;

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var a = Clock.Time / .85 % 1 * 360;
            var sw = w * .14;
            dc.PushTransform(new RotateTransform(a, w / 2, w / 2));
            Arc(dc, Pen(P.Gold, sw, true), new Point(w / 2, w / 2), w / 2 - sw / 2, 0, 270);
            dc.Pop();
        }
    }

    // ==================================================================== ping heartbeat

    /// <summary>HomeUi.kt PingPulse: a live heartbeat next to the ping, in the ping's colour, faster when the ping is high.</summary>
    public sealed class PingPulse : Drawn
    {
        static readonly (double u, double v)[] beat = Build();

        static (double, double)[] Build()
        {
            var l = new List<(double, double)>();
            for (var i = 0; i <= 4; i++) l.Add((i * .025, .12 * Math.Sin(i / 4.0 * Math.PI)));
            l.Add((.13, -.12)); l.Add((.16, 1)); l.Add((.20, -.35)); l.Add((.24, 0)); l.Add((.30, 0));
            for (var i = 1; i <= 7; i++) l.Add((.30 + i * .02, .22 * Math.Sin(i / 7.0 * Math.PI)));
            l.Add((1, 0));
            return l.ToArray();
        }

        static double At(double u)
        {
            for (var i = 1; i < beat.Length; i++)
                if (u <= beat[i].u)
                    return beat[i - 1].v + (beat[i].v - beat[i - 1].v) * ((u - beat[i - 1].u) / Math.Max(beat[i].u - beat[i - 1].u, 1e-4));
            return 0;
        }

        List<long> from = new(), to = new();
        readonly Tween morph = new(1);
        double phase, time, shotAt = -9;
        long? ping;
        Color col;

        public PingPulse() => FlowDirection = FlowDirection.LeftToRight;

        public void Set(IReadOnlyList<long> values, long? p)
        {
            ping = p;
            if (!values.SequenceEqual(to))
            {
                from = to;
                to = values.ToList();
                if (to.Count >= 2) shotAt = time;
                morph.Snap(0);
                morph.To(1, .7);
            }
            Live = to.Count >= 2;
            InvalidateVisual();
        }

        protected override void Frame(double dt)
        {
            time += dt;
            phase += dt / Orb.BeatPeriod(ping);
            morph.Step(dt);
        }

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var h = RenderSize.Height;
            col = ping is { } pv ? Orb.PingColor(pv) : P.Gold;
            var y0 = h * .74;
            var amp = h * .62;
            if (to.Count < 2)
            {
                dc.DrawGeometry(null, Pen(A(P.Gold, .25), 1.4, true), Poly(false, beat.Select(x => Pt(w * (.1 + .8 * x.u), y0 - amp * x.v)).ToArray()));
                return;
            }
            double Base(List<long> vs, double f)
            {
                if (vs.Count < 2) return 0;
                double lo = vs.Min(), span = Math.Max(vs.Max() - lo, 10);
                var pos = f * (vs.Count - 1);
                var i = Math.Min((int)pos, vs.Count - 2);
                var v = vs[i] + (vs[i + 1] - vs[i]) * (pos - i);
                return ((v - lo) / span - .5) * h * .2;
            }
            var m = morph.Value;
            double Y(double x, double v)
            {
                var f = Math.Clamp(x / w, 0, 1);
                return y0 - (Base(from, f) * (1 - m) + Base(to, f) * m) - amp * v;
            }
            const double beats = 2;
            var p0 = phase;
            var pts = new List<Point>();
            for (var k = (int)Math.Floor(p0); k <= p0 + beats + 1; k++)
                foreach (var (u, v) in beat)
                {
                    var ph = k + u;
                    if (ph < p0 - .05 || ph > p0 + beats + .05) continue;
                    var x = (ph - p0) / beats * w;
                    pts.Add(Pt(x, Y(x, v)));
                }
            if (pts.Count > 1)
            {
                var path = Poly(false, pts.ToArray());
                dc.PushClip(new RectangleGeometry(new Rect(0, -h, w, h * 3)));
                dc.DrawGeometry(null, Pen(Horizontal(0, w, Clear, A(col, .22)), 5, true), path);
                dc.DrawGeometry(null, Pen(Horizontal(0, w, Clear, A(col, .55), col), 1.6, true), path);
                dc.Pop();
            }
            var uh = (p0 + beats) % 1;
            var head = Pt(w, Y(w, At(uh)));
            Circle(dc, A(col, .25), head, 4);
            Circle(dc, A(White, .9), head, 1.5);
            var since = ((uh - .16) % 1 + 1) % 1;
            if (since < .6)
            {
                var q = since / .6;
                var x = w - since / beats * w;
                Ring(dc, A(col, (1 - q) * .7), Pt(x, Y(x, 1)), 2 + 9 * q, 1.4 - .8 * q);
            }
            var st = time - shotAt;
            if (st is >= 0 and <= .7)
            {
                var q = st / .7;
                Ring(dc, A(col, (1 - q) * .8), head, 3 + 13 * q, 1.8 - 1.2 * q);
                var b = Pt(head.X + 14 * (q * q * (3 - 2 * q)), head.Y);
                Circle(dc, A(col, (1 - q) * .35), b, 3);
                Circle(dc, A(White, 1 - q), b, 1.2);
            }
        }
    }
}
