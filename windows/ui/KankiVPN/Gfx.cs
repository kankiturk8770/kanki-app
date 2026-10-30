using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Media;

namespace KankiVPN
{
    /// <summary>
    /// What the Android app's Compose drawing gives for free, for the drawn parts in Draw.cs: colours with
    /// alpha, Compose's colour mixing (Oklab), gradients in absolute coordinates, arcs, round pens.
    /// Sizes are in WPF units, which are the Android dp.
    /// </summary>
    public static class Gfx
    {
        /// <summary>Compose's c.copy(alpha = a): the same colour with alpha a (not multiplied).</summary>
        public static Color A(Color c, double a) => Color.FromArgb((byte)Math.Round(Math.Clamp(a, 0, 1) * 255), c.R, c.G, c.B);

        /// <summary>The colour's own alpha times f.</summary>
        public static Color Fade(Color c, double f) => Color.FromArgb((byte)Math.Round(Math.Clamp(c.A / 255.0 * f, 0, 1) * 255), c.R, c.G, c.B);

        public static double Alpha(Color c) => c.A / 255.0;

        public static readonly Color White = Colors.White, Black = Colors.Black, Clear = Color.FromArgb(0, 0, 0, 0);

        // ---- Compose lerp(Color, Color, f): mixed in Oklab, alpha straight
        static double Lin(double c) => c <= .04045 ? c / 12.92 : Math.Pow((c + .055) / 1.055, 2.4);
        static double Enc(double c) => c <= .0031308 ? c * 12.92 : 1.055 * Math.Pow(c, 1 / 2.4) - .055;

        static (double L, double a, double b) ToLab(Color c)
        {
            double r = Lin(c.R / 255.0), g = Lin(c.G / 255.0), b = Lin(c.B / 255.0);
            double l = Math.Cbrt(.4122214708 * r + .5363325363 * g + .0514459929 * b);
            double m = Math.Cbrt(.2119034982 * r + .6806995451 * g + .1073969566 * b);
            double s = Math.Cbrt(.0883024619 * r + .2817188376 * g + .6299787005 * b);
            return (.2104542553 * l + .7936177850 * m - .0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + .4505937099 * s,
                .0259040371 * l + .7827717662 * m - .8086757660 * s);
        }

        static Color FromLab(double L, double A_, double B_, double alpha)
        {
            double l = L + .3963377774 * A_ + .2158037573 * B_;
            double m = L - .1055613458 * A_ - .0638541728 * B_;
            double s = L - .0894841775 * A_ - 1.2914855480 * B_;
            l = l * l * l; m = m * m * m; s = s * s * s;
            double r = 4.0767416621 * l - 3.3077115913 * m + .2309699292 * s;
            double g = -1.2684380046 * l + 2.6097574011 * m - .3413193965 * s;
            double b = -.0041960863 * l - .7034186147 * m + 1.7076147010 * s;
            byte P(double v) => (byte)Math.Round(Math.Clamp(Enc(Math.Clamp(v, 0, 1)), 0, 1) * 255);
            return Color.FromArgb((byte)Math.Round(Math.Clamp(alpha, 0, 1) * 255), P(r), P(g), P(b));
        }

        public static Color Lerp(Color a, Color b, double f)
        {
            f = Math.Clamp(f, 0, 1);
            if (f <= 0) return a;
            if (f >= 1) return b;
            var x = ToLab(a);
            var y = ToLab(b);
            return FromLab(x.L + (y.L - x.L) * f, x.a + (y.a - x.a) * f, x.b + (y.b - x.b) * f, Alpha(a) + (Alpha(b) - Alpha(a)) * f);
        }

        // ---- easings of Compose
        static double Bezier(double x1, double y1, double x2, double y2, double t)
        {
            // solve x(u) = t, then y(u)
            double u = t;
            for (var i = 0; i < 8; i++)
            {
                var x = 3 * (1 - u) * (1 - u) * u * x1 + 3 * (1 - u) * u * u * x2 + u * u * u - t;
                var d = 3 * (1 - u) * (1 - u) * x1 + 6 * (1 - u) * u * (x2 - x1) + 3 * u * u * (1 - x2);
                if (Math.Abs(d) < 1e-6) break;
                u = Math.Clamp(u - x / d, 0, 1);
            }
            return 3 * (1 - u) * (1 - u) * u * y1 + 3 * (1 - u) * u * u * y2 + u * u * u;
        }

        public static double FastOutSlowIn(double t) => Bezier(.4, 0, .2, 1, Math.Clamp(t, 0, 1));
        public static double LinearOutSlowIn(double t) => Bezier(0, 0, .2, 1, Math.Clamp(t, 0, 1));

        // ---- brushes
        static readonly Dictionary<Color, SolidColorBrush> solids = new();

        public static SolidColorBrush Solid(Color c)
        {
            if (solids.TryGetValue(c, out var b)) return b;
            b = new SolidColorBrush(c);
            b.Freeze();
            if (solids.Count > 4000) solids.Clear();
            solids[c] = b;
            return b;
        }

        static GradientStopCollection Stops(IList<Color> cs)
        {
            var s = new GradientStopCollection(cs.Count);
            for (var i = 0; i < cs.Count; i++) s.Add(new GradientStop(cs[i], cs.Count == 1 ? 0 : (double)i / (cs.Count - 1)));
            return s;
        }

        static GradientStopCollection Stops(IList<(double at, Color c)> cs)
        {
            var s = new GradientStopCollection(cs.Count);
            foreach (var (at, c) in cs) s.Add(new GradientStop(c, at));
            return s;
        }

        static T Done<T>(T b) where T : Freezable
        {
            b.Freeze();
            return b;
        }

        /// <summary>Compose Brush.radialGradient(colors, center, radius) (absolute, evenly spaced).</summary>
        public static Brush Radial(Point c, double r, params Color[] cs) => Done(new RadialGradientBrush(Stops(cs))
        {
            MappingMode = BrushMappingMode.Absolute, Center = c, GradientOrigin = c, RadiusX = Math.Max(r, .01), RadiusY = Math.Max(r, .01),
        });

        public static Brush Radial(Point c, double r, params (double, Color)[] cs) => Done(new RadialGradientBrush(Stops(cs))
        {
            MappingMode = BrushMappingMode.Absolute, Center = c, GradientOrigin = c, RadiusX = Math.Max(r, .01), RadiusY = Math.Max(r, .01),
        });

        /// <summary>Compose Brush.linearGradient(colors, start, end) in absolute coordinates.</summary>
        public static Brush Linear(Point a, Point b, params Color[] cs) => Done(new LinearGradientBrush(Stops(cs))
        {
            MappingMode = BrushMappingMode.Absolute, StartPoint = a, EndPoint = b,
        });

        public static Brush Linear(Point a, Point b, params (double, Color)[] cs) => Done(new LinearGradientBrush(Stops(cs))
        {
            MappingMode = BrushMappingMode.Absolute, StartPoint = a, EndPoint = b,
        });

        /// <summary>Compose Brush.linearGradient(colors) without points: corner to corner of the box.</summary>
        public static Brush Diagonal(Rect box, params Color[] cs) => Linear(box.TopLeft, box.BottomRight, cs);

        public static Brush Vertical(double y0, double y1, params Color[] cs) => Linear(new Point(0, y0), new Point(0, y1), cs);
        public static Brush Horizontal(double x0, double x1, params Color[] cs) => Linear(new Point(x0, 0), new Point(x1, 0), cs);

        // ---- pens and shapes
        public static Pen Pen(Brush b, double w, bool round = false, bool roundJoin = false)
        {
            var p = new Pen(b, Math.Max(w, .01));
            if (round) p.StartLineCap = p.EndLineCap = PenLineCap.Round;
            if (roundJoin || round) p.LineJoin = PenLineJoin.Round;
            p.Freeze();
            return p;
        }

        public static Pen Pen(Color c, double w, bool round = false, bool roundJoin = false) => Pen(Solid(c), w, round, roundJoin);

        public static void Circle(DrawingContext dc, Brush? fill, Point c, double r) =>
            dc.DrawEllipse(fill, null, c, Math.Max(r, 0), Math.Max(r, 0));

        public static void Circle(DrawingContext dc, Color fill, Point c, double r) => Circle(dc, Solid(fill), c, r);

        public static void Ring(DrawingContext dc, Brush b, Point c, double r, double w) =>
            dc.DrawEllipse(null, Pen(b, w), c, Math.Max(r, 0), Math.Max(r, 0));

        public static void Ring(DrawingContext dc, Color col, Point c, double r, double w) => Ring(dc, Solid(col), c, r, w);

        public static void Line(DrawingContext dc, Color c, Point a, Point b, double w, bool round = true) =>
            dc.DrawLine(Pen(c, w, round), a, b);

        public static void Line(DrawingContext dc, Brush br, Point a, Point b, double w, bool round = true) =>
            dc.DrawLine(Pen(br, w, round), a, b);

        static Point OnCircle(Point c, double rx, double ry, double deg)
        {
            var a = deg * Math.PI / 180;
            return new Point(c.X + rx * Math.Cos(a), c.Y + ry * Math.Sin(a));
        }

        /// <summary>Compose drawArc: degrees clockwise from 3 o'clock, inside the box (topLeft, size).</summary>
        public static Geometry Arc(Rect box, double start, double sweep)
        {
            var c = new Point(box.X + box.Width / 2, box.Y + box.Height / 2);
            double rx = box.Width / 2, ry = box.Height / 2;
            if (Math.Abs(sweep) >= 359.99) return new EllipseGeometry(c, rx, ry);
            var g = new StreamGeometry();
            using (var x = g.Open())
            {
                x.BeginFigure(OnCircle(c, rx, ry, start), false, false);
                x.ArcTo(OnCircle(c, rx, ry, start + sweep), new Size(rx, ry), 0, Math.Abs(sweep) > 180,
                    sweep >= 0 ? SweepDirection.Clockwise : SweepDirection.Counterclockwise, true, true);
            }
            g.Freeze();
            return g;
        }

        public static void Arc(DrawingContext dc, Pen p, Point c, double r, double start, double sweep) =>
            dc.DrawGeometry(null, p, Arc(new Rect(c.X - r, c.Y - r, r * 2, r * 2), start, sweep));

        public static Geometry Poly(bool closed, params Point[] pts)
        {
            var g = new StreamGeometry();
            using (var x = g.Open())
            {
                x.BeginFigure(pts[0], closed, closed);
                for (var i = 1; i < pts.Length; i++) x.LineTo(pts[i], true, true);
            }
            g.Freeze();
            return g;
        }

        public static Rect RectOf(double x, double y, double w, double h) => new(x, y, Math.Max(w, 0), Math.Max(h, 0));

        public static Point Pt(double x, double y) => new(x, y);
    }

    /// <summary>One of Gfx's easings as a WPF easing function (for the page and sheet animations).</summary>
    public sealed class Eased : System.Windows.Media.Animation.IEasingFunction
    {
        readonly Func<double, double> f;
        public Eased(Func<double, double> f) => this.f = f;
        public double Ease(double t) => f(t);
    }

    /// <summary>
    /// One clock for every drawn animation (Android: withFrameNanos with Motion.frameNs): at most 60 frames a
    /// second, and stopped (fixed time, no frames) in screenshot mode.
    /// </summary>
    public static class Clock
    {
        static event Action<double>? tick;
        static bool hooked;
        static TimeSpan last = TimeSpan.Zero;

        /// <summary>Seconds since start (the screenshots use a fixed time).</summary>
        public static double Time { get; private set; }

        /// <summary>Screenshot mode: no frames, time stays at <see cref="Time"/>.</summary>
        public static bool Frozen { get; private set; }

        public static void Freeze(double at)
        {
            Frozen = true;
            Time = at;
        }

        /// <summary>Called every frame with the seconds since the last one (at most 0.1).</summary>
        public static event Action<double> Tick
        {
            add
            {
                tick += value;
                if (!hooked && !Frozen)
                {
                    hooked = true;
                    CompositionTarget.Rendering += OnFrame;
                }
            }
            remove
            {
                tick -= value;
                if (tick == null && hooked)
                {
                    hooked = false;
                    last = TimeSpan.Zero;
                    CompositionTarget.Rendering -= OnFrame;
                }
            }
        }

        static void OnFrame(object? sender, EventArgs e)
        {
            var now = ((RenderingEventArgs)e).RenderingTime;
            if (last != TimeSpan.Zero && (now - last).TotalMilliseconds < 1000.0 / 61) return;
            var dt = last == TimeSpan.Zero ? 0 : Math.Min(.1, (now - last).TotalSeconds);
            last = now;
            Time += dt;
            tick?.Invoke(dt);
        }
    }

    /// <summary>java.util.Random, so the stars and runners sit exactly where the Android app puts them.</summary>
    public sealed class JavaRandom
    {
        long seed;

        public JavaRandom(long s) => seed = (s ^ 0x5DEECE66DL) & ((1L << 48) - 1);

        int Next(int bits)
        {
            seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
            return (int)((ulong)seed >> (48 - bits));
        }

        public float NextFloat() => Next(24) / (float)(1 << 24);

        public int NextInt(int bound)
        {
            if ((bound & -bound) == bound) return (int)((bound * (long)Next(31)) >> 31);
            int bits, val;
            do
            {
                bits = Next(31);
                val = bits % bound;
            } while (bits - val + (bound - 1) < 0);
            return val;
        }
    }
}
