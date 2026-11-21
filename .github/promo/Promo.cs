// Frames of the real Windows app for the promo video (copied into the app only by .github/workflows/promo-windows.yml).
// The window is the real MainWindow with sample data; time is stepped by hand, 30 frames a second.
using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace KankiVPN
{
    public partial class App
    {
        const double PromoScale = 2.4;

        static void PromoTime(double t) =>
            typeof(Clock).GetProperty("Time", BindingFlags.Public | BindingFlags.Static)!.SetValue(null, t);

        static void PromoTick(double dt) =>
            (typeof(Clock).GetField("tick", BindingFlags.NonPublic | BindingFlags.Static)?.GetValue(null) as Action<double>)?.Invoke(dt);

        static void PromoInvalidate(DependencyObject d)
        {
            (d as UIElement)?.InvalidateVisual();
            for (var i = 0; i < VisualTreeHelper.GetChildrenCount(d); i++) PromoInvalidate(VisualTreeHelper.GetChild(d, i));
        }

        async Task PromoGrab(MainWindow w, string path)
        {
            PromoInvalidate(w);
            await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
            w.UpdateLayout();
            var root = (FrameworkElement)w.Content;
            var bmp = new RenderTargetBitmap((int)(root.ActualWidth * PromoScale), (int)(root.ActualHeight * PromoScale), 96 * PromoScale, 96 * PromoScale, PixelFormats.Pbgra32);
            var bg = new DrawingVisual();
            using (var dc = bg.RenderOpen())
                dc.DrawRectangle(Brushes.Black, null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
            bmp.Render(bg);
            bmp.Render(root);
            BitmapSource img = bmp;
            if (root.FlowDirection == FlowDirection.RightToLeft)
                img = new TransformedBitmap(bmp, new ScaleTransform(-1, 1));
            var enc = new JpegBitmapEncoder { QualityLevel = 92 };
            enc.Frames.Add(BitmapFrame.Create(img));
            using var f = File.Create(path);
            enc.Save(f);
        }

        async Task Promo(string dir)
        {
            Directory.CreateDirectory(dir);
            Clock.Freeze(0);
            Strings.Persian = true;
            Theme.Apply("yellow");
            var w = new MainWindow(null, new Prefs()) { Width = 460, Height = 880, ShowInTaskbar = false };
            w.WindowStartupLocation = WindowStartupLocation.Manual;
            w.Left = -30000;
            w.Top = -30000;
            w.Show();
            w.PromoSetup();
            w.PromoState("disconnected", 0);
            await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
            // let the first moments settle
            for (var i = 0; i < 60; i++) { PromoTime(i / 30.0); PromoTick(1 / 30.0); }

            var state = "disconnected";
            long sec = -1;
            for (var i = 0; i < 165; i++)
            {
                var t = i / 30.0;
                PromoTime(2 + t);
                PromoTick(1 / 30.0);
                var st = t < 0.8 ? "disconnected" : t < 2.0 ? "connecting" : "connected";
                var s = st == "connected" ? (long)(t - 2.0) : 0;
                if (st != state || s != sec) { state = st; sec = s; w.PromoState(st, s); }
                await PromoGrab(w, Path.Combine(dir, $"winhome-{i:0000}.jpg"));
            }
            w.PromoOpen("servers");
            for (var i = 0; i < 45; i++)
            {
                PromoTime(10 + i / 30.0);
                PromoTick(1 / 30.0);
                await PromoGrab(w, Path.Combine(dir, $"winservers-{i:0000}.jpg"));
            }
            w.Quitting = true;
            w.Close();
        }
    }

    public partial class MainWindow
    {
        static Server PromoServer(string cc, string proto, bool tunnel, string name = "", string? city = null, double lat = 0, double lon = 0) => new()
        {
            Id = cc.ToLowerInvariant() + (tunnel ? "@tunnel" : "") + "/" + proto, Name = name, Proto = proto, Country = cc, Tunnel = tunnel, City = city, Lat = lat, Lon = lon,
        };

        public void PromoSetup()
        {
            var list = new List<Server>();
            var ping = new Dictionary<string, long>();
            void Add(string cc, long direct, long tunnel, string name = "", string? city = null, double lat = 0, double lon = 0)
            {
                foreach (var (p, d) in new[] { ("kankimix", 0L), ("amneziawg", 1L), ("hysteria2", 2L), ("wireguard", 1L) })
                {
                    var a = PromoServer(cc, p, false, name, city, lat, lon);
                    var b = PromoServer(cc, p, true, name, city, lat, lon);
                    list.Add(a);
                    list.Add(b);
                    ping[a.Id] = direct + d;
                    ping[b.Id] = tunnel + d;
                }
            }
            Add("TR", 46, 54);
            Add("DE", 78, 84);
            Add("NL", 85, 91);
            Add("GB", 88, 93, "انگلیس", "لندن", 51.5, -0.1);
            Add("SE", 91, 95);
            account = new Account
            {
                Username = "kanki", Status = "Active", LimitGb = 60, UsedGb = 18, MaxConn = 2, Support = "https://t.me/kanki",
                Expires = DateTimeOffset.UtcNow.AddDays(23).AddHours(4).ToUnixTimeSeconds(),
                Servers = list,
            };
            foreach (var (k, v) in ping) pings[k] = v;
            pingHistory.AddRange(new long[] { 47, 46, 48, 45, 46, 47, 46, 46 });
            settings = new Settings { SplitMode = "exclude", SplitList = new List<string>() };
            serviceVersion = "1.3.4";
            ShowAccount();
            ShowSettings();
            Open("home");
        }

        public void PromoState(string st, long elapsed)
        {
            var srv = account!.Servers[0];
            status = st == "disconnected" ? new Status() : new Status
            {
                State = st, Server = srv, KillSwitch = true,
                Since = st == "connected" ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() - elapsed : 0,
            };
            if (st == "connected")
            {
                rxRate = 1_420_000;
                txRate = 128_000;
                (DownText.Text, DownUnit.Text) = Rate(rxRate);
                (UpText.Text, UpUnit.Text) = Rate(txRate);
            }
            ShowStatus();
        }

        public void PromoOpen(string page) => Open(page);
    }
}
