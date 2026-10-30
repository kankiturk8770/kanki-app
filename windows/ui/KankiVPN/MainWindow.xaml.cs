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
        long rxRate, txRate;
        DateTime lastAt;
        bool polling, busy, loadingSettings, ready;
        string? openCountry;
        FrameworkElement? lastPage;
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
            SetGauge(0, "");
            SpeedPhase("", false);
            SpeedTiles(null);
            ConnectBtn.MouseLeftButtonUp += (_, e) => { if (ConnectBtn.IsEnabled) Connect_Click(ConnectBtn, e); };
            ConnectBtn.KeyDown += (_, e) => { if (e.Key is Key.Enter or Key.Space && ConnectBtn.IsEnabled) Connect_Click(ConnectBtn, e); };
            Theme.Changed += () => { ModeColors(); if (IsLoaded) ShowStatus(); };
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
                rxRate = (long)((status.Rx - lastRx) / secs);
                txRate = (long)((status.Tx - lastTx) / secs);
                DownText.Text = Mbps(rxRate * 8 / 1e6);
                UpText.Text = Mbps(txRate * 8 / 1e6);
            }
            else if (status.State != "connected")
            {
                DownText.Text = UpText.Text = "--";
                rxRate = txRate = 0;
            }
            lastRx = status.Rx;
            lastTx = status.Tx;
            lastAt = now;
        }

        static string Mbps(double v) => v.ToString(v < 100 ? "0.0" : "0", CultureInfo.InvariantCulture);

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
            var shown = new FrameworkElement[] { LoginPage, HomePage, ServersPage, SpeedPage, SettingsPage }.FirstOrDefault(x => x.Visibility == Visibility.Visible);
            if (shown != null && shown != lastPage) Enter(shown);
            lastPage = shown;
            Backdrop.Calm = login;
        }

        /// <summary>A page coming in: fades in and slides up 14 in 260 ms, as on Android.</summary>
        static void Enter(FrameworkElement page)
        {
            if (Clock.Frozen || !SystemParameters.ClientAreaAnimation) return;
            var ease = new Eased(Gfx.FastOutSlowIn);
            var t = new TranslateTransform();
            page.RenderTransform = t;
            page.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
            t.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(14, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
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
            ModeColors();
            if (IsLoaded) ShowStatus();
        }

        /// <summary>The tiles' icons and labels: the chosen one a little lighter and bold, the others softer.</summary>
        void ModeColors()
        {
            foreach (var (tile, icon, text, col) in new[]
            {
                (ModeSmart, ModeSmartIcon, ModeSmartText, Theme.P.ModeSmart),
                (ModeGame, ModeGameIcon, ModeGameText, Theme.P.ModeGame),
                (ModeAnti, ModeAntiIcon, ModeAntiText, Theme.P.ModeAnti),
            })
            {
                var sel = tile.IsChecked == true;
                icon.Fill = Gfx.Solid(sel ? Gfx.Lerp(col, Colors.White, .18) : Gfx.A(col, .8));
                text.Foreground = Gfx.Solid(sel ? Theme.P.Text : Gfx.A(Theme.P.Text, .78));
                text.FontWeight = sel ? FontWeights.Black : FontWeights.Bold;
            }
            ModeHintIcon.Fill = Gfx.Solid(prefs.Mode == "gaming" ? Theme.P.ModeGame : prefs.Mode == "anti" ? Theme.P.ModeAnti : Theme.P.ModeSmart);
            ConnectBtn.Game = prefs.Mode == "gaming";
        }

        /// <summary>The map and the connect button share what is left over the cards (HomeUi.kt HomeContent).</summary>
        void HomeBoxes_SizeChanged(object sender, SizeChangedEventArgs e)
        {
            Map.Height = Math.Clamp(MapBox.ActualHeight, 76, 250);
            ConnectBtn.Diameter = Math.Clamp((OrbBox.ActualHeight - 8) / Orb.Footprint, 96, 142);
        }

        async Task PingAll()
        {
            if (pipe == null || account == null || account.Servers.Count == 0) return;
            PingAllBtn.IsEnabled = false;
            PingAllBtn.Visibility = Visibility.Hidden;
            PingAllSpin.Visibility = Visibility.Visible;
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
            PingAllBtn.Visibility = Visibility.Visible;
            PingAllSpin.Visibility = Visibility.Collapsed;
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
                var lit = open || g.Any(s => s.Id == cur);
                // ServersUi.kt CountryCard: glass, lit while open or in use
                var card = new Glass { CornerRadius = new CornerRadius(20), Selected = lit, Margin = new Thickness(0, 8, 0, 0) };
                var stack = new StackPanel();
                var head = new Button { Style = (Style)FindResource("Plain"), Height = 52, Padding = new Thickness(0) };
                var row = new DockPanel { Margin = new Thickness(12, 0, 12, 0) };
                var badge = Flag(first.Country, 32);
                if (badge is Border fb) fb.BorderBrush = Gfx.Solid(lit ? Theme.P.Gold : Gfx.A(Theme.P.Accent, .7));
                row.Children.Add(Docked(badge, Dock.Left, new Thickness(0, 0, 12, 0)));
                var best = BestPing(g);
                row.Children.Add(Docked(CardPing(best == long.MaxValue ? null : best, open), Dock.Right, new Thickness(0)));
                var names = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                var nm = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Left };
                nm.Children.Add(new TextBlock { Text = Countries.Name(first.Country, first.Name), FontSize = 14, FontWeight = FontWeights.Bold, Foreground = Gfx.Solid(Theme.P.Text) });
                if (g.Any(s => s.Id == cur))
                    nm.Children.Add(new Ellipse { Width = 6, Height = 6, Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center, Fill = Gfx.Solid(Theme.P.Connected) });
                names.Children.Add(nm);
                names.Children.Add(new TextBlock { Text = Countries.City(first.Country, first.City), FontSize = 11, Foreground = Gfx.Solid(Theme.P.Sub), HorizontalAlignment = HorizontalAlignment.Left });
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

        /// <summary>ServersUi.kt CardPing: the best ping with a small open / closed triangle.</summary>
        static UIElement CardPing(long? ms, bool open)
        {
            var col = ms is { } m ? Orb.PingColor(m) : Theme.P.Sub;
            var p = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.LeftToRight, VerticalAlignment = VerticalAlignment.Center };
            var tri = open
                ? Gfx.Poly(true, new Point(0, 9 * .25), new Point(9, 9 * .25), new Point(4.5, 9 * .85))
                : Gfx.Poly(true, new Point(0, 9 * .8), new Point(9, 9 * .8), new Point(4.5, 9 * .2));
            p.Children.Add(new Path { Data = tri, Fill = Gfx.Solid(open ? Theme.P.Gold : col), Width = 9, Height = 9, VerticalAlignment = VerticalAlignment.Center });
            p.Children.Add(new TextBlock
            {
                Text = ms == null ? "-" : ms < 0 ? "✕" : ms + " ms", FontSize = 12, FontWeight = FontWeights.SemiBold,
                FontFamily = (FontFamily)FindResourceStatic("Poppins"), Foreground = Gfx.Solid(col), Margin = new Thickness(8, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            });
            return p;
        }

        static object FindResourceStatic(string key) => Application.Current.Resources[key];

        long BestPing(IEnumerable<Server> g) =>
            g.Select(s => pings.TryGetValue(s.Id, out var v) && v > 0 ? v : long.MaxValue).DefaultIfEmpty(long.MaxValue).Min();

        static string PingBrush(long ms) => ms == long.MaxValue || ms <= 0 ? "Muted" : ms < 120 ? "Connected" : ms < 250 ? "Warn" : "Danger";

        // the two columns: through Iran (tunnel) and direct, in one box (ServersUi.kt CountryCard)
        UIElement Cells(List<Server> list, string? cur)
        {
            var box = new Border
            {
                CornerRadius = new CornerRadius(16), BorderThickness = new Thickness(1), Padding = new Thickness(8), Margin = new Thickness(12, 0, 12, 12),
                Background = Gfx.Solid(Color.FromArgb(0x08, 255, 255, 255)), BorderBrush = Gfx.Solid(Gfx.A(Theme.P.Gold, .18)),
            };
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
            grid.ColumnDefinitions.Add(new ColumnDefinition());
            string Fam(string p) => p == "amneziawg" ? "amnezia" : p;
            var fams = new[] { "kankimix", "microtun", "hysteria2", "amnezia", "wireguard", "vless" };
            var lines = list.GroupBy(s => Fam(s.Proto)).OrderBy(x => Array.IndexOf(fams, x.Key) < 0 ? 99 : Array.IndexOf(fams, x.Key))
                .SelectMany(x =>
                {
                    var t = x.Where(s => s.Tunnel).ToList();
                    var d = x.Where(s => !s.Tunnel).ToList();
                    return Enumerable.Range(0, Math.Max(t.Count, d.Count)).Select(i => (t.ElementAtOrDefault(i), d.ElementAtOrDefault(i)));
                }).ToList();
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            foreach (var _ in lines) grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (var c = 0; c < 2; c++)
            {
                var t = new TextBlock
                {
                    Text = Strings.T(c == 0 ? "Tunnel" : "Direct"), FontSize = 11, FontWeight = FontWeights.Black, HorizontalAlignment = HorizontalAlignment.Center,
                    Margin = new Thickness(0, 0, 0, 4), Foreground = Gfx.Solid(c == 0 ? Theme.P.Gold : Theme.P.Sub),
                };
                Grid.SetColumn(t, c * 2);
                grid.Children.Add(t);
            }
            for (var r = 0; r < lines.Count; r++)
                for (var c = 0; c < 2; c++)
                {
                    var srv = c == 0 ? lines[r].Item1 : lines[r].Item2;
                    var cell = Cell(srv, srv != null && srv.Id == cur);
                    Grid.SetColumn(cell, c * 2);
                    Grid.SetRow(cell, r + 1);
                    grid.Children.Add(cell);
                }
            box.Child = grid;
            return box;
        }

        /// <summary>ModesUi.kt protoColor: the protocol's colour bar.</summary>
        static Color ProtoColor(string p) => p switch
        {
            "kankimix" => Theme.P.Mix,
            "microtun" => Theme.P.Connected,
            "amneziawg" => Theme.P.ModeAnti,
            "wireguard" => Theme.P.ModeDns,
            "hysteria2" => Theme.P.Connected,
            _ => Theme.P.Gold,
        };

        /// <summary>ServersUi.kt CfgCell: colour bar, name, signal bars and ping; the chosen one gold. Empty: "—".</summary>
        FrameworkElement Cell(Server? s, bool selected)
        {
            var p = Theme.P;
            var b = new Border
            {
                Height = 44, CornerRadius = new CornerRadius(12), Margin = new Thickness(0, 0, 0, 4), Padding = new Thickness(8, 0, 8, 0),
                BorderThickness = new Thickness(selected ? 1.4 : 1),
                BorderBrush = Gfx.Solid(selected ? p.Gold : Gfx.A(p.Gold, s != null ? .14 : .05)),
                Background = selected
                    ? new LinearGradientBrush(Gfx.A(p.Gold, .26), Gfx.A(p.Gold, .07), 0)
                    : new LinearGradientBrush(Color.FromArgb(0x12, 255, 255, 255), Color.FromArgb(0x05, 255, 255, 255), 0),
            };
            if (s == null)
            {
                b.Child = new TextBlock { Text = "—", FontSize = 12, Foreground = Gfx.Solid(Gfx.A(p.Sub, .5)), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                return b;
            }
            var ms = pings.TryGetValue(s.Id, out var v) ? v : 0;
            long? ping = ms == 0 ? null : ms;
            var col = ping is { } q ? Orb.PingColor(q) : p.Sub;
            var d = new DockPanel { VerticalAlignment = VerticalAlignment.Center };
            d.Children.Add(Docked(new Border { Width = 3, Height = 20, CornerRadius = new CornerRadius(1.5), Background = Gfx.Solid(ProtoColor(s.Proto)) }, Dock.Left, new Thickness(0, 0, 8, 0)));
            var right = new StackPanel { Orientation = Orientation.Horizontal, FlowDirection = FlowDirection.LeftToRight, VerticalAlignment = VerticalAlignment.Center };
            right.Children.Add(Bars(ping, col));
            right.Children.Add(new TextBlock
            {
                Text = ping == null ? "-" : ping < 0 ? "✕" : ping.ToString(), FontSize = 12, FontWeight = FontWeights.Bold,
                FontFamily = (FontFamily)FindResource("Poppins"), Foreground = Gfx.Solid(col), Margin = new Thickness(4, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center,
            });
            d.Children.Add(Docked(right, Dock.Right, new Thickness(4, 0, 0, 0)));
            d.Children.Add(new TextBlock
            {
                Text = ProtoName(s.Proto), FontSize = 12, FontWeight = FontWeights.Bold, Foreground = Gfx.Solid(selected ? p.GoldLight : p.Text),
                HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Center, TextTrimming = TextTrimming.CharacterEllipsis, TextWrapping = TextWrapping.NoWrap,
            });
            b.Child = d;
            var btn = new Button { Style = (Style)FindResource("Plain"), Content = b, ToolTip = s.Name };
            btn.Click += async (_, _) => await Pick(s);
            return btn;
        }

        /// <summary>ServersUi.kt SignalBars: three bars from the ping (under 80 three, under 160 two, else one).</summary>
        static UIElement Bars(long? ping, Color c)
        {
            var n = ping == null || ping < 0 ? 0 : ping < 80 ? 3 : ping < 160 ? 2 : 1;
            var g = new Grid { Width = 12, Height = 11, VerticalAlignment = VerticalAlignment.Center };
            var bw = 12 / 4.2;
            for (var i = 0; i < 3; i++)
            {
                var hh = 11 * (.4 + .3 * i);
                g.Children.Add(new Rectangle
                {
                    Width = bw, Height = hh, RadiusX = bw / 2, RadiusY = bw / 2, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Bottom,
                    Margin = new Thickness(i * bw * 1.6, 0, 0, 0), Fill = Gfx.Solid(i < n ? c : Gfx.A(c, .22)),
                });
            }
            return g;
        }

        static T Docked<T>(T e, Dock d, Thickness m) where T : FrameworkElement
        {
            DockPanel.SetDock(e, d);
            e.Margin = m;
            return e;
        }

        /// <summary>A round badge with the country code (Windows has no flag emoji).</summary>
        /// <summary>Kit.kt FlagBadge: the flag in a dark round badge with a gold ring.</summary>
        FrameworkElement Flag(string? cc, double size)
        {
            var b = new Border
            {
                Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), BorderThickness = new Thickness(1.5),
                Background = new RadialGradientBrush(Theme.P.Surface, Theme.P.Panel), BorderBrush = Gfx.Solid(Gfx.A(Theme.P.Gold, .55)),
            };
            if (WorldMap.Flag(cc) is { } img)
            {
                var i = new Image { Source = img, Width = size * .56, Height = size * .56, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
                RenderOptions.SetBitmapScalingMode(i, BitmapScalingMode.HighQuality);
                b.Child = i;
            }
            else
            {
                var t = new TextBlock { Text = string.IsNullOrEmpty(cc) ? "··" : cc, FontSize = size * 0.34, FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, FlowDirection = FlowDirection.LeftToRight };
                t.SetResourceReference(TextBlock.ForegroundProperty, "Text");
                b.Child = t;
            }
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
            var orb = st is "connecting" or "reconnecting" ? "connecting" : on ? "on" : "off";
            ConnectBtn.State = orb;
            var head = Strings.T(st switch
            {
                "connecting" => "Connecting…",
                "connected" => "Connected",
                "reconnecting" => "Reconnecting…",
                "error" => "Could not connect",
                _ => "Connect",
            });
            var line = on && status.Since > 0
                ? Duration(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - status.Since)
                : orb == "connecting" ? Strings.T("Tap to cancel")
                : Strings.T(prefs.Manual ? "Manual" : prefs.Mode == "gaming" ? "Gaming" : prefs.Mode == "anti" ? "Anti-filter" : "Smart");
            ConnectBtn.Text(head, line, on && status.Since > 0);
            var ms0 = srv != null && pings.TryGetValue(srv.Id, out var p0) && p0 > 0 ? p0 : 0;
            ConnectBtn.Live(on && ms0 > 0 ? ms0 : null, on ? pingHistory : new List<long>(), rxRate + txRate);
            // the background glows in the mode's colour while connected
            var modeKey = prefs.Mode == "gaming" ? "ModeGame" : prefs.Mode == "anti" ? "ModeAnti" : "ModeSmart";
            Backdrop.Accent = on ? modeKey : "Primary";

            ServerCard.Visibility = on && srv != null ? Visibility.Visible : Visibility.Collapsed;
            if (srv != null)
            {
                CardName.Text = Countries.Name(srv.Country, srv.Name);
                var city = Countries.City(srv.Country, srv.City);
                CardSub.Text = (city != "" ? city + " · " : "") + ProtoName(srv.Proto) + (srv.Tunnel ? " · " + Strings.T("Tunnel") : "");
                CardFlag.Source = WorldMap.Flag(srv.Country);
            }
            var green = Theme.P.Connected;
            StablePill.Background = Gfx.Solid(Gfx.A(green, .1));
            StablePill.BorderBrush = Gfx.Solid(Gfx.A(green, .5));
            StableDot.Fill = StableText.Foreground = Gfx.Solid(green);

            var exit = on ? srv : null;
            LocText.Text = exit != null
                ? Strings.T("Your IP:") + " " + Countries.Name(exit.Country, exit.Name)
                : Strings.T("Your location:") + " " + Countries.Name("IR", "Iran");
            var chip = on ? Theme.P.Connected : Theme.P.Gold;
            LocChip.Tint = on ? "Connected" : "Primary";
            LocDot.Fill = Gfx.Solid(chip);
            LocDot2.Fill = Gfx.Solid(Gfx.A(chip, .5));

            var ms = srv != null && pings.TryGetValue(srv.Id, out var p) && p > 0 ? p : 0;
            PingText.Text = on && ms > 0 ? ms.ToString() : "--";
            PingText.Foreground = Gfx.Solid(on && ms > 0 ? Orb.PingColor(ms) : Theme.P.Sub);
            PingLine.Set(on ? pingHistory : new List<long>(), on && ms > 0 ? ms : null);
            DrawMap(srv, on);

            if (!string.IsNullOrEmpty(status.Error) && st == "error") ShowError(status.Error!);
            ConnectBtn.IsEnabled = !busy && srv != null;
            ConnectBtn.Opacity = ConnectBtn.IsEnabled ? 1 : .55;
        }

        void ShowError(string msg)
        {
            ModeHint.Text = (status.Blocked ? Strings.T("Internet is blocked until the VPN is back (kill switch).") + " " : "") + msg;
        }

        /// <summary>The map: every server's country (one pin each), you in Iran, the chosen server lit.</summary>
        void DrawMap(Server? srv, bool on)
        {
            var pinsList = new List<MapPin>();
            if (account != null)
            {
                var seen = new HashSet<string>();
                foreach (var s in account.Servers)
                {
                    var cc = s.Country ?? "";
                    if (cc == "" || !seen.Add(cc)) continue;
                    var (lat, lon) = s.Lat != 0 || s.Lon != 0 ? (s.Lat, s.Lon) : Countries.Of(cc) is { } i ? (i.Lat, i.Lon) : (0d, 0d);
                    if (lat == 0 && lon == 0) continue;
                    pinsList.Add(new MapPin(lat, lon, cc, srv?.Country == cc));
                }
            }
            var accent = !on && status.State == "disconnected" ? "Primary"
                : prefs.Mode == "gaming" ? "ModeGame" : prefs.Mode == "anti" ? "ModeAnti" : "ModeSmart";
            Map.Set(pinsList, (35.7, 51.4), "IR", on, accent);
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
            DataText.Text = left < 0 ? "∞" : Jalali.Fa(left.ToString("0", CultureInfo.InvariantCulture));
            var days = account.Expires > 0 ? (account.Expires - DateTimeOffset.UtcNow.ToUnixTimeSeconds()) / 86400.0 : -1;
            DaysText.Text = account.Expires <= 0 ? "∞" : Jalali.Fa(Math.Max(0, Math.Ceiling(days)).ToString(CultureInfo.InvariantCulture));
            var active = account.Status == "Active" || account.Status == "";
            StatusText.Text = Strings.T(active ? "Active" : account.Status);
            StatusText.SetResourceReference(TextBlock.ForegroundProperty, active ? "Connected" : "Danger");
            StatusDot.SetResourceReference(Shape.FillProperty, active ? "Connected" : "Danger");
            // the bar is the data used (none when the data is unlimited)
            var used = account.LimitGb > 0 ? Math.Clamp(account.UsedGb / account.LimitGb, 0, 1) : -1;
            DataBarBox.Visibility = used < 0 ? Visibility.Collapsed : Visibility.Visible;
            DataBar.Background = used > .85
                ? new LinearGradientBrush(Theme.P.Warn, Theme.P.Danger, 0)
                : new LinearGradientBrush(new GradientStopCollection { new(Theme.P.GoldDeep, 0), new(Theme.P.Gold, .5), new(Theme.P.GoldLight, 1) }, 0);
            DataBar.Width = 0;
            DataBar.Dispatcher.InvokeAsync(() =>
            {
                if (DataBar.Parent is FrameworkElement f && used >= 0) DataBar.Width = f.ActualWidth * used;
            }, DispatcherPriority.Loaded);
            AccountSub.Text = account.Username;

            AccUser.Text = account.Username;
            AccStatus.Text = StatusText.Text;
            AccLeft.Text = left < 0 ? Strings.T("Unlimited") : left.ToString("0.00", CultureInfo.InvariantCulture) + " GB";
            AccUsed.Text = account.UsedGb.ToString("0.00", CultureInfo.InvariantCulture) + " GB";
            AccTime.Text = Jalali.Remaining(account.Expires);
            AccExpiry.Text = account.Expires > 0 ? Jalali.Date(account.Expires) : "—";
            AccConn.Text = account.MaxConn > 0 ? Jalali.Fa(account.MaxConn.ToString()) : "—";
            // SettingsUi.kt AccountSheet: the bar is the data left, red under 10%, amber under 25%
            var frac = left < 0 ? 1 : Math.Clamp(left / Math.Max(0.01, account.LimitGb), 0, 1);
            var barCol = frac < .1 ? Theme.P.Danger : frac < .25 ? Theme.P.Warn : Theme.P.Connected;
            AccBar.Background = new LinearGradientBrush(Gfx.A(barCol, .65), barCol, 0);
            AccBar.Dispatcher.InvokeAsync(() =>
            {
                if (AccBar.Parent is FrameworkElement f) AccBar.Width = f.ActualWidth * Math.Max(.02, frac);
            }, DispatcherPriority.Loaded);
            var pill = active ? Theme.P.Connected : Theme.P.Danger;
            AccStatusPill.Background = Gfx.Solid(Gfx.A(pill, .10));
            AccStatusPill.BorderBrush = Gfx.Solid(Gfx.A(pill, .7));
            AccStatus.Foreground = Gfx.Solid(pill);

            if (!string.IsNullOrEmpty(account.Notice)) ModeHint.Text = account.Notice;
            FillServers();
            ShowStatus();
        }

        void Account_Click(object sender, RoutedEventArgs e)
        {
            if (account == null) return;
            AccMsg.Text = "";
            ShowSheet();
        }

        void CloseSheet_Click(object sender, RoutedEventArgs e) => AccountSheet.Visibility = Visibility.Collapsed;

        /// <summary>The account sheet comes in like Android's: the dark backdrop fades in, the card grows from 0.92.</summary>
        void ShowSheet()
        {
            AccountSheet.Visibility = Visibility.Visible;
            if (Clock.Frozen || !SystemParameters.ClientAreaAnimation) return;
            var ease = new Eased(Gfx.FastOutSlowIn);
            AccountSheet.BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(200)) { EasingFunction = ease });
            var sc = new ScaleTransform(.92, .92);
            AccountCard.RenderTransform = sc;
            var grow = new DoubleAnimation(.92, 1, TimeSpan.FromMilliseconds(240)) { EasingFunction = ease };
            sc.BeginAnimation(ScaleTransform.ScaleXProperty, grow);
            sc.BeginAnimation(ScaleTransform.ScaleYProperty, grow);
        }

        /// <summary>A tap on the dark backdrop closes the sheet; a tap on the card does not.</summary>
        void Sheet_Backdrop(object sender, MouseButtonEventArgs e) => AccountSheet.Visibility = Visibility.Collapsed;

        void Sheet_Card(object sender, MouseButtonEventArgs e) => e.Handled = true;

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
                ShowSheet();
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
            try
            {
                SpDown.Text = SpUp.Text = SpPing.Text = SpJitter.Text = "–";
                SpeedTiles(null);
                SpeedPhase("ping", true);
                SetGauge(0, "ping");
                var (ping, jitter, lost) = await sp.Latency(10, ct);
                SpPing.Text = ping < 0 ? "–" : ping.ToString();
                SpJitter.Text = jitter < 0 ? "–" : jitter.ToString();
                SpeedTiles(ping < 0 ? null : ping);
                SpeedLoss(lost * 10);
                SpeedPhase("down", true);
                var down = await sp.Download(8, 6, v => Dispatcher.Invoke(() => SetGauge(v, "down")), ct);
                SpDown.Text = Mbps(down);
                SpeedPhase("up", true);
                var up = await sp.Upload(6, 4, v => Dispatcher.Invoke(() => SetGauge(v, "up")), ct);
                SpUp.Text = Mbps(up);
                SpeedPhase("done", false);
                SetGauge(down, "done");
            }
            catch (OperationCanceledException)
            {
                SpeedPhase("", false);
            }
            catch (Exception ex)
            {
                SpeedPhase("", false);
                SpLoss.Text = ex.Message;
            }
            SpUsed.Text = Strings.T("Data used by this test:") + " " + (sp.UsedBytes / 1048576.0).ToString("0.00", CultureInfo.InvariantCulture) + " MB";
            SpeedBtn.Content = Strings.T("Test again");
            speedCts = null;
        }

        /// <summary>The three step chips (SpeedUi.kt PhaseChip): spinning while it runs, a tick when done.</summary>
        void SpeedPhase(string phase, bool running)
        {
            var order = new[] { "ping", "down", "up", "done" };
            var cur = Array.IndexOf(order, phase);
            foreach (var (key, box, mark, spin, text) in new[]
            {
                ("ping", ChipPing, ChipPingMark, ChipPingSpin, ChipPingText),
                ("down", ChipDown, ChipDownMark, ChipDownSpin, ChipDownText),
                ("up", ChipUp, ChipUpMark, ChipUpSpin, ChipUpText),
            })
            {
                var me = Array.IndexOf(order, key);
                var done = cur > me || phase == "done";
                var active = running && cur == me;
                var c = active ? Theme.P.Gold : done ? Theme.P.Connected : Gfx.A(Theme.P.Sub, .6);
                box.Background = Gfx.Solid(Gfx.A(c, .10));
                box.BorderBrush = Gfx.Solid(Gfx.A(c, .45));
                text.Foreground = Gfx.Solid(c);
                spin.Visibility = active ? Visibility.Visible : Visibility.Collapsed;
                mark.Visibility = active ? Visibility.Collapsed : Visibility.Visible;
                mark.Kind = done ? "check" : "dot";
                mark.Width = mark.Height = done ? 14 : 6;
                mark.Fill = Gfx.Solid(done ? Theme.P.Connected : c);
            }
        }

        /// <summary>The gauge, the big number and the line under it (SpeedUi.kt).</summary>
        void SetGauge(double value, string phase)
        {
            var tint = phase == "up" ? "Accent" : phase == "done" ? "Connected" : "Primary";
            var c = Glass.TintOf(tint);
            Gauge.Set(value, tint);
            GaugeValue.Text = value.ToString("0.0", CultureInfo.InvariantCulture);
            GaugeGlow.Color = c;
            GaugeLabel.Foreground = Gfx.Solid(c);
            GaugeLabel.Text = phase switch
            {
                "ping" => Strings.T("Measuring ping…"),
                "up" => Strings.T("Upload") + " · Mbps",
                "down" or "done" => Strings.T("Download") + " · Mbps",
                _ => "Mbps",
            };
        }

        /// <summary>The four result tiles' icons and units (SpeedUi.kt ResultTile).</summary>
        void SpeedTiles(long? ping)
        {
            foreach (var (icon, box, c) in new[]
            {
                (SpDownIcon, SpDownIconBox, Theme.P.Gold),
                (SpUpIcon, SpUpIconBox, Theme.P.Accent),
                (SpPingIcon, SpPingIconBox, ping is { } q ? Orb.PingColor(q) : Theme.P.Sub),
                (SpJitterIcon, SpJitterIconBox, Theme.P.Sub),
            })
            {
                icon.Fill = Gfx.Solid(c);
                box.Background = Gfx.Solid(Gfx.A(c, .14));
            }
            SpDownUnit.Text = SpUpUnit.Text = "Mbps";
            SpPingUnit.Text = SpJitterUnit.Text = "ms";
        }

        void SpeedLoss(int percent)
        {
            SpLoss.Text = Strings.T("Lost:") + " " + Jalali.Fa(percent + "%");
            SpLossDot.Fill = Gfx.Solid(percent == 0 ? Theme.P.Connected : percent < 5 ? Theme.P.Warn : Theme.P.Danger);
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
                // SettingsUi.kt ThemePicker: the theme's own black, its three colours, its gold edge
                var tp = Theme.Of(name);
                var sel = Theme.Current == name;
                var b = new Border
                {
                    CornerRadius = new CornerRadius(16), BorderThickness = new Thickness(sel ? 2 : 1), Padding = new Thickness(8, 12, 8, 12),
                    BorderBrush = Gfx.Solid(sel ? tp.Gold : Gfx.A(tp.Gold, .3)), Background = new LinearGradientBrush(tp.BgMid, tp.BgBottom, 90),
                };
                var st = new StackPanel();
                var dots = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
                foreach (var c in new[] { tp.Gold, tp.Accent, tp.Connected })
                    dots.Children.Add(new Ellipse { Width = 14, Height = 14, Margin = new Thickness(2, 0, 2, 0), Fill = Gfx.Solid(c) });
                st.Children.Add(dots);
                var t = new TextBlock { Text = Strings.T(label), HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 0), FontSize = 11, FontWeight = FontWeights.Bold, Foreground = Gfx.Solid(sel ? tp.Gold : tp.Sub) };
                st.Children.Add(t);
                b.Child = st;
                btn.Content = b;
                btn.Click += (_, _) =>
                {
                    prefs.Theme = name;
                    prefs.Save();
                    Theme.Apply(name, animate: true);
                    BuildThemeCards();
                    ShowStatus();
                    FillServers();
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
                SpDown.Text = "48.6";
                SpUp.Text = "12.3";
                SpPing.Text = "74";
                SpJitter.Text = "6";
                SpeedTiles(74);
                SpeedLoss(0);
                SpeedPhase("done", false);
                SpUsed.Text = Strings.T("Data used by this test:") + " 91.55 MB";
                SpeedBtn.Content = Strings.T("Test again");
                SetGauge(48.6, "done");
            }
            if (page == "login") account = null;
            Open(page switch { "home-on" or "home-off" or "account" => "home", _ => page });
            if (page == "account") ShowSheet();
            if (page == "settings") SplitPanel.Visibility = Visibility.Visible;
            ShowStatus();
        }
    }
}
