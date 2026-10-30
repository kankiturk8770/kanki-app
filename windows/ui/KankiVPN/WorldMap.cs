using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using static KankiVPN.Gfx;

namespace KankiVPN
{
    /// <summary>A server (or you) on the map.</summary>
    public sealed record MapPin(double Lat, double Lon, string? Iso, bool Selected);

    /// <summary>
    /// WorldMap.kt, drawn the same way: no frame, straight on the background; dark land with borders in the theme
    /// colour, your country in the theme's userCountry colour, glowing pins with a flag for every server; connected,
    /// the map zooms onto the route and a neon arc runs from you to the server. The four edges fade into the
    /// background. The still layer (countries) is drawn once into a picture and only again when the view or the
    /// colours change; every frame only the pins and the arc are drawn.
    /// </summary>
    public sealed class WorldMap : Drawn
    {
        sealed record Country(string Iso, Geometry Path, double MinLon, double MaxLon, double MinLat, double MaxLat);

        static List<Country>? countries;

        /// <summary>Assets/countries.bin: Natural Earth countries, thinned like MapView.kt (windows/tools/packmap.py).</summary>
        static List<Country> Countries()
        {
            if (countries != null) return countries;
            var list = new List<Country>();
            try
            {
                var s = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/countries.bin"))!.Stream;
                using var r = new BinaryReader(s);
                r.ReadBytes(5);
                var n = r.ReadInt32();
                for (var i = 0; i < n; i++)
                {
                    var iso = new string(r.ReadChars(2));
                    var rings = r.ReadUInt16();
                    double mnX = 999, mxX = -999, mnY = 999, mxY = -999;
                    var g = new StreamGeometry { FillRule = FillRule.EvenOdd };
                    using (var x = g.Open())
                    {
                        for (var k = 0; k < rings; k++)
                        {
                            var cnt = r.ReadUInt16();
                            for (var j = 0; j < cnt; j++)
                            {
                                var lon = r.ReadInt16() / 100.0;
                                var lat = r.ReadInt16() / 100.0;
                                var p = new Point(lon, -lat);
                                if (j == 0) x.BeginFigure(p, true, true);
                                else x.LineTo(p, true, false);
                                mnX = Math.Min(mnX, lon); mxX = Math.Max(mxX, lon); mnY = Math.Min(mnY, lat); mxY = Math.Max(mxY, lat);
                            }
                        }
                    }
                    g.Freeze();
                    list.Add(new Country(iso, g, mnX, mxX, mnY, mxY));
                }
            }
            catch
            {
                // no map data: pins and the arc are still drawn
            }
            return countries = list;
        }

        static readonly Dictionary<string, ImageSource?> flags = new();

        /// <summary>The country's flag (Assets/flags, Noto's waving flags like the Android emoji), or null.</summary>
        public static ImageSource? Flag(string? iso)
        {
            if (string.IsNullOrEmpty(iso) || iso.Length != 2) return null;
            iso = iso.ToUpperInvariant();
            if (flags.TryGetValue(iso, out var f)) return f;
            try
            {
                var b = new BitmapImage();
                b.BeginInit();
                b.UriSource = new Uri($"pack://application:,,,/Assets/flags/{iso}.png");
                b.CacheOption = BitmapCacheOption.OnLoad;
                b.EndInit();
                b.Freeze();
                f = b;
            }
            catch
            {
                f = null;
            }
            return flags[iso] = f;
        }

        sealed record View(double Lon0, double Lon1, double Lat0, double Lat1);

        static double L(double a, double b, double t) => a + (b - a) * t;

        static View Fit(List<(double lat, double lon)> pts, double aspect, double minSpan)
        {
            double lon0 = pts.Min(p => p.lon), lon1 = pts.Max(p => p.lon), lat0 = pts.Max(p => p.lat), lat1 = pts.Min(p => p.lat);
            var w = Math.Max(lon1 - lon0, minSpan) * 1.55;
            var h = Math.Max(lat0 - lat1, minSpan / aspect) * 1.9;
            if (w / h < aspect) w = h * aspect; else h = w / aspect;
            var cx = (lon0 + lon1) / 2;
            var cy = (lat0 + lat1) / 2 + h * .10;
            return new View(cx - w / 2, cx + w / 2, cy + h / 2, cy - h / 2);
        }

        // ---- what is shown
        List<MapPin> pins = new();
        (double lat, double lon)? user;
        string? userIso;
        bool connected;
        string accent = "Primary";

        readonly Tween zoom = new(0);
        double drawV, drawFrom, drawT = 1, drawDur, drawDelay;
        double drawTarget;

        public WorldMap()
        {
            Live = true;
            FlowDirection = FlowDirection.LeftToRight;
            IsHitTestVisible = false;
        }

        /// <summary>accent: theme colour name of the chosen server and the dashed line before connecting.</summary>
        public void Set(List<MapPin> servers, (double lat, double lon)? you, string? youIso, bool on, string accentName)
        {
            pins = servers;
            user = you;
            userIso = youIso;
            accent = accentName;
            if (on != connected)
            {
                connected = on;
                zoom.To(on ? 1 : 0, 1.3);
                drawFrom = drawV;
                drawTarget = on ? 1 : 0;
                drawDur = on ? 1.4 : .15;
                drawDelay = on ? .7 : 0;
                drawT = 0;
            }
            if (Clock.Frozen)
            {
                zoom.Snap(on ? 1 : 0);
                drawV = drawTarget = on ? 1 : 0;
                drawT = 1;
            }
            InvalidateVisual();
        }

        protected override void Frame(double dt)
        {
            zoom.Step(dt);
            if (drawT < 1)
            {
                if (drawDelay > 0) drawDelay -= dt;
                else
                {
                    drawT = Math.Min(1, drawT + dt / drawDur);
                    drawV = drawFrom + (drawTarget - drawFrom) * FastOutSlowIn(drawT);
                }
            }
        }

        // ---- the still layer
        RenderTargetBitmap? bmp;
        string? key;

        static readonly Brush maskV = Linear(new Point(0, 0), new Point(0, 1), (0, Clear), (.20, Black), (.66, Black), (1, Clear));
        static readonly Brush maskH = Linear(new Point(0, 0), new Point(1, 0), (0, Clear), (.14, Black), (.86, Black), (1, Clear));

        static Brush Relative(Brush b)
        {
            var c = ((LinearGradientBrush)b).Clone();
            c.MappingMode = BrushMappingMode.RelativeToBoundingBox;
            c.Freeze();
            return c;
        }

        static readonly Brush maskVr = Relative(maskV), maskHr = Relative(maskH);

        void Still(View bv, double w, double h, string? selIso, Color selColor, Color youColor)
        {
            var dpi = VisualTreeHelper.GetDpi(this);
            var pw = Math.Max(1, (int)Math.Ceiling(w * dpi.DpiScaleX));
            var ph = Math.Max(1, (int)Math.Ceiling(h * dpi.DpiScaleY));
            var bsx = w / (bv.Lon1 - bv.Lon0);
            var bsy = h / (bv.Lat0 - bv.Lat1);
            var border = 1 / bsx;
            var p = Theme.P;
            var dv = new DrawingVisual();
            using (var dc = dv.RenderOpen())
            {
                dc.PushOpacityMask(maskVr);
                dc.PushOpacityMask(maskHr);
                dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
                dc.PushClip(new RectangleGeometry(new Rect(0, 0, w, h)));
                dc.PushTransform(new MatrixTransform(bsx, 0, 0, bsy, -bv.Lon0 * bsx, bv.Lat0 * bsy));
                var land = Solid(A(p.Panel, .55));
                foreach (var c in Countries())
                {
                    if (c.MaxLon < bv.Lon0 || c.MinLon > bv.Lon1 || c.MaxLat < bv.Lat1 || c.MinLat > bv.Lat0) continue;
                    dc.DrawGeometry(land, null, c.Path);
                    if (c.Iso == selIso)
                    {
                        dc.DrawGeometry(Solid(A(selColor, .32)), null, c.Path);
                        dc.DrawGeometry(null, Pen(A(selColor, .30), border * 4.5, roundJoin: true), c.Path);
                        dc.DrawGeometry(null, Pen(selColor, border * 1.8, roundJoin: true), c.Path);
                    }
                    else if (c.Iso == userIso)
                    {
                        dc.DrawGeometry(Solid(A(youColor, .16)), null, c.Path);
                        dc.DrawGeometry(null, Pen(A(youColor, .25), border * 4, roundJoin: true), c.Path);
                        dc.DrawGeometry(null, Pen(A(youColor, .75), border * 1.4, roundJoin: true), c.Path);
                    }
                    else
                    {
                        dc.DrawGeometry(null, Pen(A(p.Gold, .10), border * 3, roundJoin: true), c.Path);
                        dc.DrawGeometry(null, Pen(A(p.Gold, .38), border, roundJoin: true), c.Path);
                    }
                }
                dc.Pop();
                dc.Pop();
                dc.Pop();
                dc.Pop();
            }
            var b = new RenderTargetBitmap(pw, ph, 96 * dpi.DpiScaleX, 96 * dpi.DpiScaleY, PixelFormats.Pbgra32);
            b.Render(dv);
            b.Freeze();
            bmp = b;
        }

        static double EdgeFade(Point o, double w, double h)
        {
            static double Ramp(double x, double a, double b) => Math.Clamp((x - a) / (b - a), 0, 1);
            var fx = o.X / w;
            var fy = (o.Y - w / 34 * 2.2) / h;
            var v = Math.Min(Ramp(fy, 0, .20), 1 - Ramp(fy, .66, 1));
            var hz = Math.Min(Ramp(fx, 0, .14), 1 - Ramp(fx, .86, 1));
            var e = v * hz;
            return e * e;
        }

        static void NeonDot(DrawingContext dc, Color c, Point o, double r)
        {
            Circle(dc, A(c, .15 * Alpha(c)), o, r * 3.5);
            Circle(dc, A(c, .35 * Alpha(c)), o, r * 2);
            Circle(dc, c, o, r);
            Circle(dc, White, o, r * .45);
        }

        static void FlagPin(DrawingContext dc, Point o, string? iso, Color c, double pr, double fade = 1)
        {
            var head = new Point(o.X, o.Y - pr * 2.2);
            dc.DrawGeometry(Solid(c), null, Poly(true, Pt(o.X, o.Y - pr * .3), Pt(head.X - pr * .62, head.Y + pr * .6), Pt(head.X + pr * .62, head.Y + pr * .6)));
            Circle(dc, A(c, .28), head, pr * 1.45);
            Circle(dc, c, head, pr * 1.12);
            Circle(dc, Fade(Theme.P.Panel, fade), head, pr * .96);
            if (Flag(iso) is { } f)
            {
                var s = pr * 1.3;
                dc.PushOpacity(fade);
                dc.DrawImage(f, new Rect(head.X - s / 2, head.Y - s / 2, s, s));
                dc.Pop();
            }
        }

        protected override void OnRender(DrawingContext dc)
        {
            var w = RenderSize.Width;
            var h = RenderSize.Height;
            if (w < 2 || h < 2) return;
            var p = Theme.P;
            var time = Clock.Time;
            var flow = time / 1.8 % 1;
            var pulse = time / 2 % 1;
            var aspect = w / h;
            var sel = pins.FirstOrDefault(x => x.Selected);
            var all = pins.Select(x => (x.Lat, x.Lon)).ToList();
            if (user is { } u0) all.Add(u0);
            if (all.Count == 0) all.Add((45, 20));
            var far = Fit(all, aspect, 40);
            var nearPts = new List<(double, double)>();
            if (user is { } u1) nearPts.Add(u1);
            if (sel != null) nearPts.Add((sel.Lat, sel.Lon));
            var near = Fit(nearPts.Count > 0 ? nearPts : all, aspect, 40);
            var z = zoom.Value;
            var v = new View(L(far.Lon0, near.Lon0, z), L(far.Lon1, near.Lon1, z), L(far.Lat0, near.Lat0, z), L(far.Lat1, near.Lat1, z));
            var sx = w / (v.Lon1 - v.Lon0);
            var sy = h / (v.Lat0 - v.Lat1);
            Point Proj(double lat, double lon) => new((lon - v.Lon0) * sx, (v.Lat0 - lat) * sy);

            var selColor = connected ? p.Gold : Glass.TintOf(accent);
            var youColor = p.UserCountry;
            var still = z == 0 || z == 1;
            var bv = still ? v : far;
            var k = string.Join("|", w.ToString(CultureInfo.InvariantCulture), h.ToString(CultureInfo.InvariantCulture), still ? z.ToString(CultureInfo.InvariantCulture) : "-1",
                string.Join(",", pins.Select(x => $"{x.Lat},{x.Lon},{x.Iso},{x.Selected}")), user?.ToString() ?? "", userIso ?? "", connected, accent,
                p.Gold.ToString(), p.UserCountry.ToString(), p.Panel.ToString());
            if (k != key || bmp == null)
            {
                key = k;
                Still(bv, w, h, sel?.Iso, selColor, youColor);
            }
            if (still) dc.DrawImage(bmp, new Rect(0, 0, w, h));
            else
            {
                var bsx = w / (bv.Lon1 - bv.Lon0);
                var bsy = h / (bv.Lat0 - bv.Lat1);
                dc.PushOpacityMask(maskVr);
                dc.PushOpacityMask(maskHr);
                dc.DrawRectangle(Brushes.Transparent, null, new Rect(0, 0, w, h));
                dc.PushClip(new RectangleGeometry(new Rect(0, 0, w, h)));
                dc.PushTransform(new MatrixTransform(sx / bsx, 0, 0, sy / bsy, (bv.Lon0 - v.Lon0) * sx, (v.Lat0 - bv.Lat0) * sy));
                dc.DrawImage(bmp, new Rect(0, 0, w, h));
                dc.Pop();
                dc.Pop();
                dc.Pop();
                dc.Pop();
            }
            var drawn = drawV;
            var ac = Glass.TintOf(accent);
            // before connecting: a faint dashed line from you to the chosen server
            if (user is { } uu && sel != null && drawn < 1)
            {
                var a = Proj(uu.lat, uu.lon);
                var b = Proj(sel.Lat, sel.Lon);
                var lift = Math.Sqrt((b.X - a.X) * (b.X - a.X) + (b.Y - a.Y) * (b.Y - a.Y)) * .30;
                var mid = new Point((a.X + b.X) / 2, (a.Y + b.Y) / 2 - lift);
                var g = new StreamGeometry();
                using (var x = g.Open())
                {
                    x.BeginFigure(a, false, false);
                    x.QuadraticBezierTo(mid, b, true, true);
                }
                var swd = w / 320;
                var pen = new Pen(Solid(A(ac, .6 * (1 - drawn))), swd)
                {
                    StartLineCap = PenLineCap.Round, EndLineCap = PenLineCap.Round, DashCap = PenLineCap.Round,
                    DashStyle = new DashStyle(new[] { 4.0, 4.0 }, -flow * 8),
                };
                dc.DrawGeometry(null, pen, g);
            }
            // the neon arc in the theme's "connected" colour, from you to the server
            if (user is { } u2 && sel != null && drawn > 0)
            {
                var a = Proj(u2.lat, u2.lon);
                var b = Proj(sel.Lat, sel.Lon);
                var lift = Math.Sqrt((b.X - a.X) * (b.X - a.X) + (b.Y - a.Y) * (b.Y - a.Y)) * .30;
                var mid = new Point((a.X + b.X) / 2, (a.Y + b.Y) / 2 - lift);
                Point Bez(double q) => new((1 - q) * (1 - q) * a.X + 2 * (1 - q) * q * mid.X + q * q * b.X,
                    (1 - q) * (1 - q) * a.Y + 2 * (1 - q) * q * mid.Y + q * q * b.Y);
                const int steps = 48;
                var pts = new List<Point> { a };
                for (var i = 1; i <= Math.Max(1, (int)(steps * drawn)); i++) pts.Add(Bez(i / (double)steps));
                var path = Poly(false, pts.ToArray());
                var sw = w / 240;
                foreach (var (m, al) in new[] { (5.0, .07), (3.0, .16), (1.8, .36), (1.0, 1.0) })
                    dc.DrawGeometry(null, Pen(Linear(a, b, A(p.UserCountry, al), A(p.Gold, al)), sw * m, true), path);
                dc.DrawGeometry(null, Pen(A(White, .55), sw * .3, true), path);
                if (drawn >= 1)
                    for (var kk = 0; kk < 3; kk++) NeonDot(dc, White, Bez((flow + kk / 3.0) % 1), sw * 1.2);
            }
            var pr = w / 34;
            // the other servers: a glowing dot with a small flag pin (not the one in your own country)
            foreach (var pin in pins.Where(x => !x.Selected && (user == null || x.Iso != userIso)))
            {
                var o = Proj(pin.Lat, pin.Lon);
                var e = EdgeFade(o, w, h);
                if (e <= .01) continue;
                NeonDot(dc, A(p.Gold, .85 * e), o, w / 170);
                FlagPin(dc, o, pin.Iso, A(p.Gold, .8 * e), pr * .78, e);
            }
            // the chosen server: halo, pulsing wave and a bigger pin
            if (sel != null)
            {
                var o = Proj(sel.Lat, sel.Lon);
                var r = w / 14;
                Circle(dc, Radial(o, r * 1.3, A(selColor, .32), A(selColor, .10), Clear), o, r * 1.3);
                if (connected) Ring(dc, A(selColor, (1 - pulse) * .7), o, r * (.35 + pulse * .9), w / 300);
                NeonDot(dc, selColor, o, w / 110);
                FlagPin(dc, o, sel.Iso, selColor, pr);
            }
            // you: a pulsing dot, a pin with your flag and a label
            if (user is { } u3)
            {
                var o = Proj(u3.lat, u3.lon);
                Ring(dc, A(youColor, (1 - pulse) * .55), o, w / 120 * (1.5 + pulse * 3), w / 400);
                NeonDot(dc, youColor, o, w / 125);
                if (sel == null || sel.Iso != userIso) FlagPin(dc, o, userIso, youColor, pr * .85);
                var ft = new FormattedText(Strings.T("You"), CultureInfo.CurrentUICulture, FlowDirection.LeftToRight,
                    new Typeface((FontFamily)Application.Current.Resources["Vazir"], FontStyles.Normal, FontWeights.Bold, FontStretches.Normal),
                    w / 34, Solid(p.Text), VisualTreeHelper.GetDpi(this).PixelsPerDip);
                dc.DrawText(ft, new Point(o.X + pr * 1.1, o.Y - pr * 2.2 - ft.Height / 2));
            }
        }
    }
}
