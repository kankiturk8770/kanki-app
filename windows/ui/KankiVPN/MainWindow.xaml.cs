using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;

namespace KankiVPN
{
    public partial class MainWindow : Window
    {
        readonly PipeClient? pipe; // null = screenshot mode (Demo data)
        readonly Prefs prefs;
        readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };

        Account? account;
        Settings? settings;
        Status status = new();
        string serviceVersion = "";
        readonly Dictionary<string, long> pings = new();
        readonly List<long> pingHistory = new();
        ulong lastRx, lastTx;
        DateTime lastAt;
        bool polling, busy, turning, loadingSettings, ready;
        string? openCountry;
        int tick;
        CancellationTokenSource? speedCts;

        public bool Quitting { get; set; }

        public MainWindow(PipeClient? pipe, Prefs prefs)
        {
            this.pipe = pipe;
            this.prefs = prefs;
            InitializeComponent();
            FlowDirection = Strings.Persian ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            LangFa.IsChecked = Strings.Persian;
            LangEn.IsChecked = !Strings.Persian;
            switch (prefs.Mode)
            {
                case "gaming": ModeGame.IsChecked = true; break;
                case "anti": ModeAnti.IsChecked = true; break;
                default: ModeSmart.IsChecked = true; break;
            }
            BuildThemeCards();
            DrawGauge(0, "");
            ready = true;
            timer.Tick += async (_, _) => await Poll();
            Loaded += async (_, _) => await Start();
            Closing += OnClosing;
        }

        // ================================================================ start / poll

        async Task Start()
        {
            if (pipe == null) return;
            await Reload();
            await Poll();
            timer.Start();
            _ = PingAll();
        }

        async Task Reload()
        {
            try
            {
                var hello = await pipe!.Call("hello", null, 3000);
                serviceVersion = hello?["version"]?.GetValue<string>() ?? "?";
                account = await pipe.Call<Account>("account");
                settings = await pipe.Call<Settings>("settings");
                ServiceOk();
            }
            catch (Exception e)
            {
                ServiceDown(e);
            }
            ShowAccount();
            ShowSettings();
        }

        async Task Poll()
        {
            if (pipe == null || polling) return;
            polling = true;
            tick++;
            try
            {
                if (serviceVersion == "") await Reload(); // the service was down: start over once it answers
                status = await pipe.Call<Status>("status", null, 5000) ?? new Status();
                ServiceOk();
                Rates();
                if (status.State == "connected" && tick % 5 == 0 && status.Server != null) await PingCurrent();
                if (tick % 180 == 0) _ = PingAll();
                ShowStatus();
                if (LogPanel.Visibility == Visibility.Visible && tick % 3 == 0) await LoadLog();
            }
            catch (Exception e)
            {
                ServiceDown(e);
            }
            finally
            {
                polling = false;
            }
        }

        void Rates()
        {
            var now = DateTime.UtcNow;
            if (status.State == "connected" && lastAt != default && status.Rx >= lastRx && status.Tx >= lastTx)
            {
                var secs = Math.Max(0.2, (now - lastAt).TotalSeconds);
                DownText.Text = Mbps((status.Rx - lastRx) * 8 / secs / 1e6);
                UpText.Text = Mbps((status.Tx - lastTx) * 8 / secs / 1e6);
            }
            else if (status.State != "connected")
            {
                DownText.Text = UpText.Text = "--";
            }
            lastRx = status.Rx;
            lastTx = status.Tx;
            lastAt = now;
        }

        static string Mbps(double v) => v.ToString(v < 10 ? "0.0" : "0", CultureInfo.InvariantCulture);

        void ServiceOk()
        {
            ServiceBanner.Visibility = Visibility.Collapsed;
            App.Tray?.SetState(status.State);
        }

        void ServiceDown(Exception e)
        {
            serviceVersion = "";
            ServiceText.Text = e is ServiceException se && se.Message == Strings.T("The VPN service could not be verified. Reinstall Kanki VPN.")
                ? se.Message
                : Strings.T("The VPN service is not running. Reinstall Kanki VPN, or start the \"Kanki VPN\" service in Windows Services.");
            ServiceBanner.Visibility = Visibility.Visible;
        }

        // ================================================================ pages

        void Tab_Checked(object sender, RoutedEventArgs e)
        {
            if (HomePage == null) return; // during InitializeComponent
            ShowPage();
            if (sender == TabServers && pings.Count == 0) _ = PingAll();
        }

        void ShowPage()
        {
            var login = account == null;
            NavBar.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
            AccountBtn.Visibility = login ? Visibility.Hidden : Visibility.Visible;
            LoginPage.Visibility = login ? Visibility.Visible : Visibility.Collapsed;
            HomePage.Visibility = !login && TabHome.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            ServersPage.Visibility = !login && TabServers.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            SpeedPage.Visibility = !login && TabSpeed.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            SettingsPage.Visibility = !login && TabSettings.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            if (login) AccountSheet.Visibility = Visibility.Collapsed;
        }

        public void Open(string page)
        {
            switch (page)
            {
                case "servers": TabServers.IsChecked = true; break;
                case "speed": TabSpeed.IsChecked = true; break;
                case "settings": TabSettings.IsChecked = true; break;
                default: TabHome.IsChecked = true; break;
            }
            ShowPage();
        }

        // ================================================================ servers, modes and choosing

        static string ProtoName(string p) => p switch
        {
            "amneziawg" => "AmneziaWG",
            "wireguard" => "WireGuard",
            "kankimix" => "KANKI MIX",
            "microtun" => "microtun",
            "vless" => "VLESS",
            "hysteria2" => "Hysteria2",
            _ => p,
        };

        Server? ById(string? id) => id == null ? null : account?.Servers.FirstOrDefault(s => s.Id == id);

        /// <summary>The server the button connects to: the one in use, the one picked by hand,
        /// else the best of the chosen mode (Android Modes.kt).</summary>
        Server? Chosen()
        {
            if (account == null || account.Servers.Count == 0) return null;
            if (status.State != "disconnected" && ById(status.Server?.Id) is { } cur) return cur;
            if (prefs.Manual && ById(prefs.LastServer) is { } picked) return picked;
            return Best(prefs.Mode) ?? ById(prefs.LastServer) ?? account.Servers[0];
        }

        /// <summary>Smart: the lowest ping of all. Gaming: KANKI MIX and microtun first (made for
        /// games). Anti-filter: the hidden protocols first (VLESS, Hysteria2, AmneziaWG).</summary>
        Server? Best(string mode)
        {
            if (account == null) return null;
            string[] order = mode switch
            {
                "gaming" => new[] { "kankimix", "microtun", "amneziawg", "wireguard" },
                "anti" => new[] { "vless", "hysteria2", "amneziawg" },
                _ => Array.Empty<string>(),
            };
            IEnumerable<Server> pool = account.Servers;
            if (order.Length > 0)
            {
                var fam = pool.Where(s => order.Contains(s.Proto)).ToList();
                if (fam.Count > 0) pool = fam;
            }
            long P(Server s) => pings.TryGetValue(s.Id, out var v) && v > 0 ? v : long.MaxValue;
            int Rank(Server s) => order.Length == 0 ? 0 : Array.IndexOf(order, s.Proto) < 0 ? 99 : Array.IndexOf(order, s.Proto);
            // with pings: the lowest; the family order only breaks near-ties (within 15 ms)
            var list = pool.ToList();
            if (list.Any(s => P(s) != long.MaxValue))
                return list.OrderBy(s => P(s) == long.MaxValue ? long.MaxValue : P(s) / 15 * 15 + Rank(s)).First();
            return list.OrderBy(Rank).First();
        }

        void Mode_Checked(object sender, RoutedEventArgs e)
        {
            if (ready)
            {
                prefs.Mode = sender == ModeGame ? "gaming" : sender == ModeAnti ? "anti" : "smart";
                prefs.Manual = false; // a mode means "choose for me"
                prefs.Save();
            }
            ModeHint.Text = Strings.T(prefs.Mode switch
            {
                "gaming" => "Gaming: the lowest ping with KANKI MIX and microtun",
                "anti" => "Anti-filter: the hidden protocols for hard networks",
                _ => "Smart: pings every server and connects to the best",
            });
            if (IsLoaded) ShowStatus();
        }

        async Task PingAll()
        {
            if (pipe == null || account == null || account.Servers.Count == 0) return;
            PingAllBtn.IsEnabled = false;
            PingAllBar.Width = 0;
            var bar = new DoubleAnimation(0, Math.Max(0, PingAllBar.Parent is FrameworkElement f ? f.ActualWidth * 0.9 : 300), TimeSpan.FromSeconds(6));
            PingAllBar.BeginAnimation(WidthProperty, bar);
            try
            {
                var res = await pipe.Call("ping", new { servers = Array.Empty<string>() }, 60000) as JsonObject;
                if (res != null)
                    foreach (var (k, v) in res)
                        pings[k] = v?.GetValue<long>() ?? -1;
            }
            catch
            {
                // the banner says what is wrong with the service
            }
            PingAllBar.BeginAnimation(WidthProperty, null);
            PingAllBar.Width = PingAllBar.Parent is FrameworkElement p ? p.ActualWidth : 0;
            PingAllBtn.IsEnabled = true;
            FillServers();
            ShowStatus();
        }

        async Task PingCurrent()
        {
            var id = status.Server?.Id;
            if (pipe == null || id == null) return;
            try
            {
                if (await pipe.Call("ping", new { servers = new[] { id } }, 15000) is JsonObject res && res[id] is { } v)
                {
                    var ms = v.GetValue<long>();
                    pings[id] = ms;
                    if (ms > 0)
                    {
                        pingHistory.Add(ms);
                        if (pingHistory.Count > 16) pingHistory.RemoveAt(0);
                    }
                }
            }
            catch
            {
                // next round
            }
        }

        void PingAll_Click(object sender, RoutedEventArgs e) => _ = PingAll();

        // the country groups with their Tunnel / Direct columns, like ServersUi.kt
        void FillServers()
        {
            CountryList.Children.Clear();
            if (account == null) return;
            var groups = account.Servers.GroupBy(s => string.IsNullOrEmpty(s.Country) ? s.Name : s.Country!).ToList();
            CountriesChip.Text = Jalali.Fa(groups.Count + " " + Strings.T("countries"));
            ConfigsChip.Text = Jalali.Fa(account.Servers.Count + " " + Strings.T("configs"));
            var udp = account.Servers.Count(s => s.Proto != "vless");
            PingAllSub.Text = $"UDP {udp} · TCP {account.Servers.Count - udp}";
            ServersEmpty.Visibility = account.Servers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            var cur = status.State != "disconnected" ? status.Server?.Id : Chosen()?.Id;
            foreach (var g in groups.OrderBy(g => BestPing(g)))
            {
                var key = g.Key;
                var open = openCountry == key || (openCountry == null && g.Any(s => s.Id == cur));
                var first = g.First();
                var card = new Border { Style = (Style)FindResource("CardBox"), Margin = new Thickness(0, 10, 0, 0), Padding = new Thickness(12, 10, 12, 10) };
                if (g.Any(s => s.Id == cur)) card.SetResourceReference(Border.BorderBrushProperty, "EdgeStrong");
                var stack = new StackPanel();
                var head = new Button { Style = (Style)FindResource("Plain") };
                var row = new DockPanel();
                row.Children.Add(Docked(Flag(first.Country, 36), Dock.Right, new Thickness(10, 0, 0, 0)));
                var best = BestPing(g);
                var pingTxt = new TextBlock
                {
                    Text = (open ? "▾ " : "▴ ") + (best == long.MaxValue ? "—" : best + " ms"),
                    FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center, FlowDirection = FlowDirection.LeftToRight,
                };
                pingTxt.SetResourceReference(TextBlock.ForegroundProperty, PingBrush(best));
                row.Children.Add(Docked(pingTxt, Dock.Left, new Thickness(0)));
                var names = new StackPanel();
                var nm = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
                nm.Children.Add(new TextBlock { Text = Countries.Name(first.Country, first.Name), FontSize = 16, FontWeight = FontWeights.Bold });
                if (g.Any(s => s.Id == cur && status.State == "connected"))
                {
                    var dot = new Ellipse { Width = 7, Height = 7, Margin = new Thickness(6, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
                    dot.SetResourceReference(Shape.FillProperty, "Connected");
                    nm.Children.Add(dot);
                }
                names.Children.Add(nm);
                names.Children.Add(new TextBlock { Text = Countries.City(first.Country, first.City), Style = (Style)FindResource("Small"), HorizontalAlignment = HorizontalAlignment.Right });
                row.Children.Add(names);
                head.Content = row;
                head.Click += (_, _) =>
                {
                    openCountry = open ? "" : key;
                    FillServers();
                };
                stack.Children.Add(head);
                if (open) stack.Children.Add(Cells(g.ToList(), cur));
                card.Child = stack;
                CountryList.Children.Add(card);
            }
        }

        long BestPing(IEnumerable<Server> g) =>
            g.Select(s => pings.TryGetValue(s.Id, out var v) && v > 0 ? v : long.MaxValue).DefaultIfEmpty(long.MaxValue).Min();

        static string PingBrush(long ms) => ms == long.MaxValue || ms <= 0 ? "Muted" : ms < 120 ? "Connected" : ms < 250 ? "Warn" : "Danger";

        // the two columns: through Iran (tunnel) and direct
        UIElement Cells(List<Server> list, string? cur)
        {
            var box = new Border { CornerRadius = new CornerRadius(12), BorderThickness = new Thickness(1), Padding = new Thickness(8), Margin = new Thickness(0, 10, 0, 0) };
            box.SetResourceReference(Border.BorderBrushProperty, "Edge");
            box.SetResourceReference(Border.BackgroundProperty, "Surface");
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            var cols = new[] { list.Where(s => s.Tunnel).ToList(), list.Where(s => !s.Tunnel).ToList() };
            var titles = new[] { "Tunnel", "Direct" };
            var rows = Math.Max(cols[0].Count, cols[1].Count);
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (var r = 0; r < rows; r++) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (var c = 0; c < 2; c++)
            {
                var t = new TextBlock { Text = Strings.T(titles[c]), FontSize = 12, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 0, 0, 6) };
                t.SetResourceReference(TextBlock.ForegroundProperty, c == 0 ? "Primary" : "Muted");
                Grid.SetColumn(t, c);
                grid.Children.Add(t);
                for (var r = 0; r < rows; r++)
                {
                    var cell = r < cols[c].Count ? Cell(cols[c][r], cols[c][r].Id == cur) : EmptyCell();
                    Grid.SetColumn(cell, c);
                    Grid.SetRow(cell, r + 1);
                    grid.Children.Add(cell);
                }
            }
            box.Child = grid;
            return box;
        }

        FrameworkElement EmptyCell()
        {
            var b = new Border { CornerRadius = new CornerRadius(10), BorderThickness = new Thickness(1), Margin = new Thickness(3), MinHeight = 40 };
            b.SetResourceReference(Border.BorderBrushProperty, "Edge");
            b.Child = new TextBlock { Text = "—", HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Style = (Style)FindResource("Small") };
            return b;
        }

        FrameworkElement Cell(Server s, bool selected)
        {
            var ms = pings.TryGetValue(s.Id, out var v) ? v : 0;
            var btn = new Button { Style = (Style)FindResource("Plain"), Margin = new Thickness(3) };
            var b = new Border { CornerRadius = new CornerRadius(10), BorderThickness = new Thickness(selected ? 1.6 : 1), Padding = new Thickness(10, 8, 0, 8) };
            b.SetResourceReference(Border.BorderBrushProperty, selected ? "Primary" : "Edge");
            b.SetResourceReference(Border.BackgroundProperty, selected ? "Sheet" : "Card");
            var d = new DockPanel();
            // the coloured edge: the protocol's family (Android protoColor)
            var edge = new Border { Width = 3, CornerRadius = new CornerRadius(2), Margin = new Thickness(8, 0, 4, 0) };
            edge.SetResourceReference(Border.BackgroundProperty, s.Proto switch
            {
                "kankimix" or "microtun" => "ModeGame",
                "vless" or "hysteria2" => "ModeAnti",
                "amneziawg" => "Primary",
                _ => "PrimaryLight",
            });
            d.Children.Add(Docked(edge, Dock.Right, new Thickness(0)));
            var left = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.LeftToRight };
            if (ms < 0)
            {
                var x = new TextBlock { Text = "✕", FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center };
                x.SetResourceReference(TextBlock.ForegroundProperty, "Danger");
                left.Children.Add(x);
            }
            else
            {
                var t = new TextBlock { Text = ms > 0 ? ms.ToString() : "…", FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center };
                t.SetResourceReference(TextBlock.ForegroundProperty, PingBrush(ms > 0 ? ms : long.MaxValue));
                left.Children.Add(t);
            }
            left.Children.Add(Bars(ms));
            d.Children.Add(Docked(left, Dock.Left, new Thickness(0)));
            var name = new TextBlock { Text = ProtoName(s.Proto), FontWeight = FontWeights.Bold, FontSize = 13, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Center, FlowDirection = FlowDirection.LeftToRight, TextTrimming = TextTrimming.CharacterEllipsis };
            if (selected) name.SetResourceReference(TextBlock.ForegroundProperty, "Primary");
            d.Children.Add(name);
            b.Child = d;
            btn.Content = b;
            btn.ToolTip = s.Name;
            btn.Click += async (_, _) => await Pick(s);
            return btn;
        }

        /// <summary>The signal bars next to a ping (SignalBars in ServersUi.kt).</summary>
        static UIElement Bars(long ms)
        {
            var lit = ms <= 0 ? 0 : ms < 90 ? 3 : ms < 180 ? 2 : 1;
            var p = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(5, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
            for (var i = 0; i < 3; i++)
            {
                var r = new Rectangle { Width = 3, Height = 5 + i * 4, Margin = new Thickness(1, 0, 0, 0), VerticalAlignment = VerticalAlignment.Bottom, RadiusX = 1, RadiusY = 1 };
                r.SetResourceReference(Shape.FillProperty, i < lit ? PingBrush(ms) : "Edge");
                p.Children.Add(r);
            }
            return p;
        }

        static T Docked<T>(T e, Dock d, Thickness m) where T : FrameworkElement
        {
            DockPanel.SetDock(e, d);
            e.Margin = m;
            return e;
        }

        /// <summary>A round badge with the country code (Windows has no flag emoji).</summary>
        FrameworkElement Flag(string? cc, double size)
        {
            var b = new Border { Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), BorderThickness = new Thickness(1.2) };
            b.SetResourceReference(Border.BackgroundProperty, "Surface");
            b.SetResourceReference(Border.BorderBrushProperty, "PrimaryDark");
            var t = new TextBlock { Text = string.IsNullOrEmpty(cc) ? "··" : cc, FontSize = size * 0.34, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, FlowDirection = FlowDirection.LeftToRight };
            t.SetResourceReference(TextBlock.ForegroundProperty, "Text");
            b.Child = t;
            return b;
        }

        async Task Pick(Server s)
        {
            prefs.LastServer = s.Id;
            prefs.Manual = true;
            prefs.Save();
            Open("home");
            if (pipe == null) return;
            try
            {
                await pipe.Call("connect", new { server = s.Id });
                status = await pipe.Call<Status>("status") ?? new Status();
            }
            catch (Exception ex)
            {
                ShowError(ex.Message);
            }
            ShowStatus();
        }

        void ServerCard_Click(object sender, RoutedEventArgs e) => Open("servers");

        // ================================================================ Home

        void ShowStatus()
        {
            var st = status.State;
            var on = st == "connected";
            var srv = Chosen();
            StateText.Text = Strings.T(st switch
            {
                "connecting" => "Connecting…",
                "connected" => "Connected",
                "reconnecting" => "Reconnecting…",
                "error" => "Could not connect",
                _ => "Connect",
            });
            StateText.SetResourceReference(TextBlock.ForegroundProperty, st switch
            {
                "connected" => "Connected",
                "error" => "Danger",
                _ => "Primary",
            });
            SubText.Text = on && status.Since > 0
                ? Duration(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - status.Since)
                : st == "disconnected" ? Strings.T(prefs.Manual ? "Manual" : prefs.Mode == "gaming" ? "Gaming" : prefs.Mode == "anti" ? "Anti-filter" : "Smart")
                : st == "error" ? Strings.T("Tap to cancel") : "";
            SubText.SetResourceReference(TextBlock.ForegroundProperty, on ? "Text" : "Muted");
            Ring.SetResourceReference(Shape.StrokeProperty, on ? "Connected" : st == "error" ? "Danger" : "Primary");
            RingGlow.Opacity = on ? 1 : st == "disconnected" ? 0.7 : 0.9;
            Turn(st == "connecting" || st == "reconnecting");

            ServerCard.Visibility = srv != null ? Visibility.Visible : Visibility.Collapsed;
            if (srv != null)
            {
                CardName.Text = Countries.Name(srv.Country, srv.Name);
                var city = Countries.City(srv.Country, srv.City);
                CardSub.Text = (city != "" ? city + " · " : "") + ProtoName(srv.Proto) + (srv.Tunnel ? " · " + Strings.T("Tunnel") : "");
                CardCcText.Text = string.IsNullOrEmpty(srv.Country) ? "··" : srv.Country;
            }
            StablePill.Visibility = on ? Visibility.Visible : Visibility.Collapsed;

            var exit = on ? srv : null;
            LocText.Text = exit != null
                ? Strings.T("Your IP:") + " " + Countries.Name(exit.Country, exit.Name)
                : Strings.T("Your location:") + " " + Strings.T("Tehran");
            LocDot.SetResourceReference(Shape.FillProperty, on ? "Connected" : "Primary");

            var ms = srv != null && pings.TryGetValue(srv.Id, out var p) && p > 0 ? p : 0;
            PingText.Text = on && ms > 0 ? ms.ToString() : "--";
            PingText.SetResourceReference(TextBlock.ForegroundProperty, on && ms > 0 ? PingBrush(ms) : "Text");
            DrawSpark();
            DrawMap(srv, on);

            if (!string.IsNullOrEmpty(status.Error) && st == "error") ShowError(status.Error!);
            ConnectBtn.IsEnabled = !busy && srv != null;
        }

        void ShowError(string msg)
        {
            ModeHint.Text = (status.Blocked ? Strings.T("Internet is blocked until the VPN is back (kill switch).") + " " : "") + msg;
        }

        void DrawSpark()
        {
            var pts = new PointCollection();
            if (pingHistory.Count > 1)
            {
                var max = pingHistory.Max();
                var min = pingHistory.Min();
                for (var i = 0; i < pingHistory.Count; i++)
                {
                    var y = max == min ? 9 : 16 - (pingHistory[i] - min) * 14.0 / (max - min);
                    pts.Add(new Point(i * 44.0 / (pingHistory.Count - 1), y));
                }
            }
            else
            {
                pts.Add(new Point(0, 12));
                pts.Add(new Point(12, 12));
                pts.Add(new Point(16, 4));
                pts.Add(new Point(20, 16));
                pts.Add(new Point(24, 12));
                pts.Add(new Point(44, 12));
            }
            PingLine.Points = pts;
        }

        void Turn(bool on)
        {
            // one moving thing in the whole app: the ring's ticks while connecting
            if (on == turning || !SystemParameters.ClientAreaAnimation) return;
            turning = on;
            TicksTurn.BeginAnimation(RotateTransform.AngleProperty,
                on ? new DoubleAnimation(0, 360, TimeSpan.FromSeconds(3)) { RepeatBehavior = RepeatBehavior.Forever } : null);
        }

        // map: same projection as Assets/map.png (lon -12..66, lat 24..64 over 1560 x 1000)
        static Point Proj(double lat, double lon) => new((lon + 12) / 78 * 1560, (64 - lat) / 40 * 1000);

        void DrawMap(Server? srv, bool on)
        {
            MapMarks.Children.Clear();
            if (account == null) return;
            var tehran = Proj(35.7, 51.4);
            var seen = new HashSet<string>();
            foreach (var s in account.Servers)
            {
                var cc = s.Country ?? "";
                if (cc == "" || !seen.Add(cc)) continue;
                var (lat, lon) = s.Lat != 0 || s.Lon != 0 ? (s.Lat, s.Lon) : Countries.Of(cc) is { } i ? (i.Lat, i.Lon) : (0d, 0d);
                if (lat == 0 && lon == 0) continue;
                var p = Proj(lat, lon);
                if (on && srv?.Country == cc)
                {
                    // the route: an arc from you to the server, like the Android map
                    var mid = new Point((tehran.X + p.X) / 2, Math.Min(tehran.Y, p.Y) - 160);
                    var fig = new PathFigure { StartPoint = tehran };
                    fig.Segments.Add(new QuadraticBezierSegment(mid, p, true));
                    var arc = new Path { Data = new PathGeometry(new[] { fig }), StrokeThickness = 6 };
                    arc.SetResourceReference(Shape.StrokeProperty, "Connected");
                    arc.Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = Theme.Col("Connected"), BlurRadius = 18, ShadowDepth = 0 };
                    MapMarks.Children.Add(arc);
                }
                Mark(p, cc, srv?.Country == cc && on ? "Connected" : "Primary");
            }
            Mark(tehran, null, "User");
        }

        void Mark(Point p, string? cc, string brush)
        {
            const double r = 34;
            var dot = new Ellipse { Width = 22, Height = 22 };
            dot.SetResourceReference(Shape.FillProperty, brush);
            dot.Effect = new System.Windows.Media.Effects.DropShadowEffect { Color = Theme.Col(brush), BlurRadius = 26, ShadowDepth = 0 };
            Canvas.SetLeft(dot, p.X - 11);
            Canvas.SetTop(dot, p.Y - 11);
            MapMarks.Children.Add(dot);
            FrameworkElement badge;
            if (cc != null)
            {
                badge = Flag(cc, r * 2);
            }
            else
            {
                var t = new TextBlock { Text = Strings.T("You"), FontSize = 30, FontWeight = FontWeights.Bold };
                t.SetResourceReference(TextBlock.ForegroundProperty, "Text");
                badge = t;
            }
            badge.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
            Canvas.SetLeft(badge, cc != null ? p.X - r : p.X + 18);
            Canvas.SetTop(badge, cc != null ? p.Y - r * 2 - 18 : p.Y - 46);
            MapMarks.Children.Add(badge);
        }

        async void Connect_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            var srv = Chosen();
            if (srv == null) return;
            busy = true;
            ConnectBtn.IsEnabled = false;
            try
            {
                if (status.State == "disconnected")
                {
                    // Smart and the other modes pick from fresh pings
                    if (!prefs.Manual && pings.Count == 0) await PingAll();
                    srv = Chosen() ?? srv;
                    prefs.LastServer = srv.Id;
                    prefs.Save();
                    await pipe.Call("connect", new { server = srv.Id });
                }
                else
                {
                    await pipe.Call("disconnect");
                    pingHistory.Clear();
                }
                status = await pipe.Call<Status>("status") ?? new Status();
            }
            catch (Exception ex)
            {
                ShowError(ex.Message);
            }
            busy = false;
            ShowStatus();
        }

        // ================================================================ account

        void ShowAccount()
        {
            ShowPage();
            if (account == null) return;
            var left = account.LimitGb > 0 ? Math.Max(0, account.LimitGb - account.UsedGb) : -1;
            DataText.Text = left < 0 ? Strings.T("Unlimited") : Jalali.Fa(left.ToString("0.#", CultureInfo.InvariantCulture)) + " " + Strings.T("GB");
            var days = account.Expires > 0 ? (account.Expires - DateTimeOffset.UtcNow.ToUnixTimeSeconds()) / 86400.0 : -1;
            DaysText.Text = account.Expires <= 0 ? Strings.T("Unlimited") : days <= 0 ? Strings.T("Expired") : Jalali.Fa(Math.Ceiling(days).ToString(CultureInfo.InvariantCulture)) + " " + Strings.T("days");
            var active = account.Status == "Active" || account.Status == "";
            StatusText.Text = Strings.T(active ? "Active" : account.Status);
            StatusText.SetResourceReference(TextBlock.ForegroundProperty, active ? "Connected" : "Warn");
            StatusDot.SetResourceReference(Shape.FillProperty, active ? "Connected" : "Warn");
            DataBar.Width = 0;
            DataBar.Dispatcher.InvokeAsync(() =>
            {
                if (DataBar.Parent is FrameworkElement f)
                    DataBar.Width = left < 0 ? f.ActualWidth : f.ActualWidth * Math.Clamp(left / Math.Max(0.01, account?.LimitGb ?? 1), 0, 1);
            }, DispatcherPriority.Loaded);
            AccountSub.Text = account.Username;

            AccUser.Text = account.Username;
            AccStatus.Text = StatusText.Text;
            AccLeft.Text = left < 0 ? Strings.T("Unlimited") : left.ToString("0.00", CultureInfo.InvariantCulture) + " GB";
            AccUsed.Text = account.UsedGb.ToString("0.00", CultureInfo.InvariantCulture) + " GB";
            AccTime.Text = Jalali.Remaining(account.Expires);
            AccExpiry.Text = account.Expires > 0 ? Jalali.Date(account.Expires) : "—";
            AccConn.Text = account.MaxConn > 0 ? Jalali.Fa(account.MaxConn.ToString()) : "—";
            AccBar.Dispatcher.InvokeAsync(() =>
            {
                if (AccBar.Parent is FrameworkElement f)
                    AccBar.Width = left < 0 ? f.ActualWidth : f.ActualWidth * Math.Clamp(left / Math.Max(0.01, account?.LimitGb ?? 1), 0, 1);
            }, DispatcherPriority.Loaded);

            if (!string.IsNullOrEmpty(account.Notice)) ModeHint.Text = account.Notice;
            FillServers();
            ShowStatus();
        }

        void Account_Click(object sender, RoutedEventArgs e)
        {
            if (account == null) return;
            AccMsg.Text = "";
            AccountSheet.Visibility = Visibility.Visible;
        }

        void CloseSheet_Click(object sender, RoutedEventArgs e) => AccountSheet.Visibility = Visibility.Collapsed;

        async void Refresh_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            AccMsg.Text = Strings.T("Loading…");
            try
            {
                account = await pipe.Call<Account>("refresh") ?? account;
                AccMsg.Text = Strings.T("Updated");
            }
            catch (Exception ex)
            {
                AccMsg.Text = ex.Message;
            }
            ShowAccount();
        }

        async void CopySub_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            try
            {
                var link = (await pipe.Call("sublink"))?.GetValue<string>() ?? "";
                Clipboard.SetText(link);
                AccMsg.Text = Strings.T("Copied");
            }
            catch (Exception ex)
            {
                AccMsg.Text = ex.Message;
            }
        }

        void Renew_Click(object sender, RoutedEventArgs e)
        {
            var url = account?.Support;
            if (string.IsNullOrEmpty(url) || !(url.StartsWith("https://") || url.StartsWith("tg://")))
            {
                AccMsg.Text = Strings.T("The panel has no support link yet.");
                AccountSheet.Visibility = Visibility.Visible;
                return;
            }
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            }
            catch (Exception ex)
            {
                AccMsg.Text = ex.Message;
            }
        }

        async void AddSub_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null || string.IsNullOrWhiteSpace(SubInput.Text)) return;
            AddSubBtn.IsEnabled = false;
            LoginError.SetResourceReference(TextBlock.ForegroundProperty, "Muted");
            LoginError.Text = Strings.T("Loading…");
            try
            {
                account = await pipe.Call<Account>("set_sub", new { input = SubInput.Text.Trim() });
                LoginError.Text = "";
                SubInput.Text = "";
                Open("home");
                ShowAccount();
                _ = PingAll();
            }
            catch (Exception ex)
            {
                LoginError.SetResourceReference(TextBlock.ForegroundProperty, "Danger");
                LoginError.Text = ex is ServiceException ? ex.Message : Strings.T("The VPN service is not running. Reinstall Kanki VPN, or start the \"Kanki VPN\" service in Windows Services.");
            }
            AddSubBtn.IsEnabled = true;
        }

        void SubInput_KeyDown(object sender, KeyEventArgs e)
        {
            if (e.Key == Key.Enter) AddSub_Click(sender, e);
        }

        async void SignOut_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            try
            {
                await pipe.Call("logout");
                account = null;
                status = new Status();
                pings.Clear();
                ShowAccount();
            }
            catch (Exception ex)
            {
                AccMsg.Text = ex.Message;
            }
        }

        // ================================================================ speed test

        async void Speed_Click(object sender, RoutedEventArgs e)
        {
            if (speedCts != null)
            {
                speedCts.Cancel();
                return;
            }
            speedCts = new CancellationTokenSource();
            var ct = speedCts.Token;
            SpeedBtn.Content = Strings.T("Stop");
            var sp = new Speed();
            void Chip(Border b, TextBlock t, string label, int state)
            {
                t.Text = (state == 2 ? "✓ " : state == 1 ? "… " : "") + Strings.T(label);
                b.SetResourceReference(Border.BorderBrushProperty, state == 0 ? "Edge" : "Primary");
            }
            try
            {
                SpDown.Text = SpUp.Text = SpPing.Text = SpJitter.Text = "--";
                Chip(ChipPing, ChipPingText, "Ping", 1);
                Chip(ChipDown, ChipDownText, "Download", 0);
                Chip(ChipUp, ChipUpText, "Upload", 0);
                var (ping, jitter, lost) = await sp.Latency(10, ct);
                SpPing.Text = ping < 0 ? "—" : ping + " ms";
                SpJitter.Text = jitter < 0 ? "—" : jitter + " ms";
                SpLoss.Text = Strings.T("Lost:") + " " + Jalali.Fa(lost * 10 + "%");
                Chip(ChipPing, ChipPingText, "Ping", 2);
                Chip(ChipDown, ChipDownText, "Download", 1);
                var down = await sp.Download(8, 6, v => Dispatcher.Invoke(() => DrawGauge(v, "Download")), ct);
                SpDown.Text = Mbps(down) + " Mbps";
                Chip(ChipDown, ChipDownText, "Download", 2);
                Chip(ChipUp, ChipUpText, "Upload", 1);
                var up = await sp.Upload(6, 4, v => Dispatcher.Invoke(() => DrawGauge(v, "Upload")), ct);
                SpUp.Text = Mbps(up) + " Mbps";
                Chip(ChipUp, ChipUpText, "Upload", 2);
                DrawGauge(down, "Download");
            }
            catch (OperationCanceledException)
            {
            }
            catch (Exception ex)
            {
                SpLoss.Text = ex.Message;
            }
            SpUsed.Text = Strings.T("Data used by this test:") + " " + (sp.UsedBytes / 1048576.0).ToString("0.00", CultureInfo.InvariantCulture) + " MB";
            SpeedBtn.Content = Strings.T("Test again");
            speedCts = null;
        }

        // the speed gauge: a 270° dial with a log scale 0..1000 Mbps and a needle (SpeedUi.kt)
        static readonly double[] Ticks = { 0, 5, 10, 25, 50, 100, 250, 500, 1000 };

        static double Frac(double v) => v <= 0 ? 0 : Math.Min(1, Math.Log10(1 + v) / Math.Log10(1001));

        void DrawGauge(double value, string label)
        {
            Gauge.Children.Clear();
            const double cx = 130, cy = 120, r = 100;
            Point At(double f, double rad)
            {
                var a = (135 + 270 * f) * Math.PI / 180;
                return new Point(cx + rad * Math.Cos(a), cy + rad * Math.Sin(a));
            }
            Path Arc(double f0, double f1, string brush, double w)
            {
                var fig = new PathFigure { StartPoint = At(f0, r) };
                fig.Segments.Add(new ArcSegment(At(f1, r), new Size(r, r), 0, (f1 - f0) * 270 > 180, SweepDirection.Clockwise, true));
                var p = new Path { Data = new PathGeometry(new[] { fig }), StrokeThickness = w, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
                p.SetResourceReference(Shape.StrokeProperty, brush);
                return p;
            }
            Gauge.Children.Add(Arc(0, 1, "Surface", 16));
            var f = Frac(value);
            if (f > 0.002) Gauge.Children.Add(Arc(0, f, "Connected", 16));
            foreach (var t in Ticks)
            {
                var tf = Frac(t);
                var a = At(tf, r - 22);
                var tick = new Line { X1 = At(tf, r - 12).X, Y1 = At(tf, r - 12).Y, X2 = At(tf, r - 17).X, Y2 = At(tf, r - 17).Y, StrokeThickness = 2 };
                tick.SetResourceReference(Shape.StrokeProperty, "Primary");
                Gauge.Children.Add(tick);
                var tx = new TextBlock { Text = t.ToString(CultureInfo.InvariantCulture), FontSize = 10 };
                tx.SetResourceReference(TextBlock.ForegroundProperty, "Text");
                tx.Measure(new Size(100, 100));
                Canvas.SetLeft(tx, a.X - tx.DesiredSize.Width / 2);
                Canvas.SetTop(tx, a.Y - tx.DesiredSize.Height / 2);
                Gauge.Children.Add(tx);
            }
            var tip = At(f, r - 34);
            var needle = new Line { X1 = cx, Y1 = cy, X2 = tip.X, Y2 = tip.Y, StrokeThickness = 4, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round };
            needle.SetResourceReference(Shape.StrokeProperty, "Connected");
            Gauge.Children.Add(needle);
            var hub = new Ellipse { Width = 16, Height = 16, StrokeThickness = 3 };
            hub.SetResourceReference(Shape.StrokeProperty, "Connected");
            hub.SetResourceReference(Shape.FillProperty, "Bg");
            Canvas.SetLeft(hub, cx - 8);
            Canvas.SetTop(hub, cy - 8);
            Gauge.Children.Add(hub);
            var big = new TextBlock { Text = value > 0 ? Mbps(value) : "0", FontSize = 40, FontWeight = FontWeights.Bold };
            big.SetResourceReference(TextBlock.ForegroundProperty, "Text");
            big.Measure(new Size(300, 100));
            Canvas.SetLeft(big, cx - big.DesiredSize.Width / 2);
            Canvas.SetTop(big, cy + 30);
            Gauge.Children.Add(big);
            GaugeLabel.Text = "Mbps" + (label != "" ? " · " + Strings.T(label) : "");
        }

        // ================================================================ settings

        static readonly (string dns, string name)[] DnsList =
        {
            ("", "Server default"),
            ("1.1.1.1, 1.0.0.1", "Cloudflare"),
            ("8.8.8.8, 8.8.4.4", "Google"),
            ("94.140.14.14, 94.140.15.15", "AdGuard (blocks ads)"),
            ("9.9.9.9, 149.112.112.112", "Quad9 (safe)"),
        };

        void ShowSettings()
        {
            loadingSettings = true;
            SignInBox.IsChecked = pipe != null && Prefs.OpenAtSignIn;
            VersionText.Text = "Kanki VPN " + typeof(App).Assembly.GetName().Version?.ToString(3)
                + (serviceVersion != "" && serviceVersion != "?" ? "  ·  service " + serviceVersion : "");
            if (settings != null)
            {
                KillBox.IsChecked = settings.KillSwitch;
                Ipv6Box.IsChecked = settings.BlockIPv6;
                LanBox.IsChecked = settings.AllowLan;
                AutoBox.IsChecked = settings.AutoConnect;
                SplitAll.IsChecked = settings.SplitMode == "all";
                SplitExclude.IsChecked = settings.SplitMode == "exclude";
                SplitInclude.IsChecked = settings.SplitMode == "include";
                SplitBox.Text = string.Join(Environment.NewLine, settings.SplitList);
                MtuBox.Text = settings.Mtu.ToString(CultureInfo.InvariantCulture);
                var cur = string.Join(", ", settings.Dns);
                DnsSub.Text = Strings.T(DnsList.FirstOrDefault(d => d.dns == cur).name ?? "Custom");
                if (cur != "" && DnsList.All(d => d.dns != cur)) DnsSub.Text = cur;
                SplitSub.Text = Strings.T(settings.SplitMode switch
                {
                    "exclude" => "Everything except these addresses",
                    "include" => "Only these addresses",
                    _ => "Everything through the VPN",
                });
            }
            SplitBox.IsEnabled = SplitAll.IsChecked != true;
            SplitNote.Visibility = SplitInclude.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            loadingSettings = false;
        }

        void Dns_Click(object sender, RoutedEventArgs e)
        {
            if (DnsChoices.Visibility == Visibility.Visible)
            {
                DnsChoices.Visibility = Visibility.Collapsed;
                return;
            }
            DnsChoices.Children.Clear();
            var cur = string.Join(", ", settings?.Dns ?? new List<string>());
            foreach (var (dns, name) in DnsList)
            {
                var rb = new RadioButton { Style = (Style)FindResource("Choice"), GroupName = "dns", Content = Strings.T(name), IsChecked = dns == cur };
                rb.Click += (_, _) =>
                {
                    if (settings == null) return;
                    settings.Dns = dns.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
                    DnsChoices.Visibility = Visibility.Collapsed;
                    _ = SaveSettings(settings);
                };
                DnsChoices.Children.Add(rb);
            }
            DnsChoices.Visibility = Visibility.Visible;
        }

        void Split_Click(object sender, RoutedEventArgs e) =>
            SplitPanel.Visibility = SplitPanel.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;

        // every switch saves at once, as on Android (no save button)
        void Setting_Changed(object sender, RoutedEventArgs e)
        {
            if (loadingSettings || settings == null) return;
            int.TryParse(MtuBox.Text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var mtu);
            var s = new Settings
            {
                KillSwitch = KillBox.IsChecked == true,
                BlockIPv6 = Ipv6Box.IsChecked == true,
                AllowLan = LanBox.IsChecked == true,
                AutoConnect = AutoBox.IsChecked == true,
                SplitMode = SplitInclude.IsChecked == true ? "include" : SplitExclude.IsChecked == true ? "exclude" : "all",
                SplitList = SplitBox.Text.Split(new[] { '\r', '\n', ',', ' ' }, StringSplitOptions.RemoveEmptyEntries).ToList(),
                Dns = settings.Dns,
                Mtu = mtu,
            };
            // "only these" needs a range first; wait until one is written
            if (s.SplitMode == "include" && s.SplitList.Count == 0)
            {
                SplitBox.IsEnabled = true;
                SaveMsg.SetResourceReference(TextBlock.ForegroundProperty, "Warn");
                SaveMsg.Text = Strings.T("Add at least one address to send through the VPN.");
                return;
            }
            _ = SaveSettings(s);
        }

        async Task SaveSettings(Settings s)
        {
            if (pipe == null) return;
            try
            {
                settings = await pipe.Call<Settings>("set_settings", new { settings = s }) ?? s;
                SaveMsg.SetResourceReference(TextBlock.ForegroundProperty, "Connected");
                SaveMsg.Text = Strings.T("Saved");
            }
            catch (Exception ex)
            {
                SaveMsg.SetResourceReference(TextBlock.ForegroundProperty, "Danger");
                SaveMsg.Text = ex.Message;
            }
            ShowSettings();
        }

        void SignIn_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                Prefs.OpenAtSignIn = SignInBox.IsChecked == true;
            }
            catch (Exception ex)
            {
                SaveMsg.Text = ex.Message;
            }
        }

        void Lang_Click(object sender, RoutedEventArgs e)
        {
            var fa = LangFa.IsChecked == true;
            if (fa == Strings.Persian) return;
            prefs.Lang = fa ? "fa" : "en";
            prefs.Save();
            Strings.Persian = fa;
            ((App)Application.Current).Rebuild("settings");
        }

        // the theme cards: name and three dots, the chosen one outlined (SettingsUi.kt)
        void BuildThemeCards()
        {
            ThemeCards.Children.Clear();
            foreach (var (name, label) in new[] { ("yellow", "Kanki yellow"), ("crimson", "Crimson") })
            {
                var btn = new Button { Style = (Style)FindResource("Plain"), Margin = new Thickness(4) };
                var b = new Border { CornerRadius = new CornerRadius(14), BorderThickness = new Thickness(Theme.Current == name ? 2 : 1), Padding = new Thickness(8, 10, 8, 10) };
                b.SetResourceReference(Border.BorderBrushProperty, Theme.Current == name ? "Primary" : "Edge");
                b.SetResourceReference(Border.BackgroundProperty, "Surface");
                var st = new StackPanel();
                var dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
                foreach (var c in Theme.Dots(name))
                    dots.Children.Add(new Ellipse { Width = 14, Height = 14, Margin = new Thickness(2), Fill = new SolidColorBrush(c) });
                st.Children.Add(dots);
                var t = new TextBlock { Text = Strings.T(label), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 6, 0, 0), FontWeight = FontWeights.SemiBold };
                t.SetResourceReference(TextBlock.ForegroundProperty, "Text");
                st.Children.Add(t);
                b.Child = st;
                btn.Content = b;
                btn.Click += (_, _) =>
                {
                    prefs.Theme = name;
                    prefs.Save();
                    Theme.Apply(name);
                    BuildThemeCards();
                    ShowStatus();
                    FillServers();
                    DrawGauge(0, "");
                };
                ThemeCards.Children.Add(btn);
            }
        }

        // ================================================================ log

        void Log_Click(object sender, RoutedEventArgs e)
        {
            LogPanel.Visibility = LogPanel.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
            if (LogPanel.Visibility == Visibility.Visible) _ = LoadLog();
        }

        async Task LoadLog()
        {
            if (pipe == null) return;
            try
            {
                var lines = await pipe.Call<List<string>>("logs") ?? new List<string>();
                var text = string.Join(Environment.NewLine, lines);
                if (text != LogBox.Text)
                {
                    LogBox.Text = text;
                    LogBox.ScrollToEnd();
                }
            }
            catch
            {
                // the banner already says the service is down
            }
        }

        void CopyLog_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                Clipboard.SetText(LogBox.Text);
            }
            catch
            {
                // the clipboard is busy; the user can try again
            }
        }

        // ================================================================ window

        void OnClosing(object? sender, CancelEventArgs e)
        {
            if (Quitting || pipe == null) return;
            // closing the window keeps the app in the tray; the VPN itself lives in the service
            e.Cancel = true;
            Hide();
            if (!prefs.TrayHintShown)
            {
                App.Tray?.Hint(Strings.T("Kanki VPN is still running in the tray."));
                prefs.TrayHintShown = true;
                prefs.Save();
            }
        }

        protected override void OnClosed(EventArgs e)
        {
            timer.Stop();
            speedCts?.Cancel();
            base.OnClosed(e);
        }

        static string Duration(long seconds)
        {
            var t = TimeSpan.FromSeconds(Math.Max(0, seconds));
            return ((int)t.TotalHours).ToString("00", CultureInfo.InvariantCulture) + t.ToString(@"\:mm\:ss", CultureInfo.InvariantCulture);
        }

        // ================================================================ screenshots (CI)

        /// <summary>Fills the window with sample data, for the screenshots CI takes.</summary>
        public void Demo(string page)
        {
            account = new Account
            {
                Username = "mahtab87", Status = "Active", LimitGb = 60, UsedGb = 18, MaxConn = 2, Support = "https://t.me/kanki",
                Expires = DateTimeOffset.UtcNow.AddDays(23).AddHours(4).ToUnixTimeSeconds(),
                Servers = new List<Server>
                {
                    new() { Id = "de@tunnel/kankimix", Name = "GERMANY", Proto = "kankimix", Country = "DE", Tunnel = true },
                    new() { Id = "de@tunnel/hysteria2", Name = "GERMANY", Proto = "hysteria2", Country = "DE", Tunnel = true },
                    new() { Id = "de@tunnel/amneziawg", Name = "GERMANY", Proto = "amneziawg", Country = "DE", Tunnel = true },
                    new() { Id = "de@tunnel/wireguard", Name = "GERMANY", Proto = "wireguard", Country = "DE", Tunnel = true },
                    new() { Id = "de/kankimix", Name = "GERMANY", Proto = "kankimix", Country = "DE" },
                    new() { Id = "de/hysteria2", Name = "GERMANY", Proto = "hysteria2", Country = "DE" },
                    new() { Id = "de/amneziawg", Name = "GERMANY", Proto = "amneziawg", Country = "DE" },
                    new() { Id = "fr/amneziawg", Name = "FRANCE", Proto = "amneziawg", Country = "FR" },
                    new() { Id = "nl/microtun", Name = "NETHERLANDS", Proto = "microtun", Country = "NL" },
                    new() { Id = "tr/vless", Name = "TURKEY", Proto = "vless", Country = "TR" },
                },
            };
            foreach (var (k, v) in new Dictionary<string, long>
            {
                ["de@tunnel/kankimix"] = 81, ["de@tunnel/hysteria2"] = -1, ["de@tunnel/amneziawg"] = 74, ["de@tunnel/wireguard"] = 77,
                ["de/kankimix"] = 68, ["de/hysteria2"] = 95, ["de/amneziawg"] = 92, ["fr/amneziawg"] = 88, ["nl/microtun"] = 95, ["tr/vless"] = 61,
            }) pings[k] = v;
            pingHistory.AddRange(new long[] { 78, 74, 80, 72, 74, 90, 74, 73 });
            settings = new Settings { SplitMode = "exclude", SplitList = new List<string> { "5.0.0.0/8" } };
            var on = page is "home-on" or "servers";
            status = on
                ? new Status { State = "connected", Server = account.Servers[2], Rx = 0, Tx = 0, KillSwitch = true, Since = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 754 }
                : new Status();
            serviceVersion = "1.1.0";
            ShowAccount();
            ShowSettings();
            if (on)
            {
                DownText.Text = "10.8";
                UpText.Text = "1.0";
            }
            if (page == "speed")
            {
                SpDown.Text = "48.6 Mbps";
                SpUp.Text = "12.3 Mbps";
                SpPing.Text = "74 ms";
                SpJitter.Text = "6 ms";
                SpLoss.Text = Strings.T("Lost:") + " " + Jalali.Fa("0%");
                ChipPingText.Text = "✓ " + Strings.T("Ping");
                ChipDownText.Text = "✓ " + Strings.T("Download");
                ChipUpText.Text = "✓ " + Strings.T("Upload");
                SpUsed.Text = Strings.T("Data used by this test:") + " 91.55 MB";
                SpeedBtn.Content = Strings.T("Test again");
                DrawGauge(48.6, "Download");
            }
            if (page == "login") account = null;
            Open(page switch { "home-on" or "home-off" or "account" => "home", _ => page });
            if (page == "account") AccountSheet.Visibility = Visibility.Visible;
            if (page == "settings") SplitPanel.Visibility = Visibility.Visible;
            ShowStatus();
        }
    }
}
