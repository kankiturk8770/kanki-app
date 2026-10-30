using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace KankiVPN
{
    /// <summary>One row of the server list.</summary>
    public sealed class ServerItem
    {
        public ServerItem(Server s, bool current)
        {
            Server = s;
            CurrentVisibility = current ? Visibility.Visible : Visibility.Collapsed;
        }

        public Server Server { get; }
        public string Name => Server.Name;
        public string Cc => string.IsNullOrEmpty(Server.Country) ? "··" : Server.Country!;
        public string Tags => MainWindow.TagsOf(Server);
        public Visibility CurrentVisibility { get; }
    }

    public partial class MainWindow : Window
    {
        readonly PipeClient? pipe; // null = screenshot mode (Demo data)
        readonly Prefs prefs;
        readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };

        Account? account;
        Settings? settings;
        Status status = new();
        string serviceVersion = "";
        bool busy;
        bool turning;
        bool polling;
        string? shownCurrent;
        int tick;

        public bool Quitting { get; set; }

        public MainWindow(PipeClient? pipe, Prefs prefs)
        {
            this.pipe = pipe;
            this.prefs = prefs;
            InitializeComponent();
            FlowDirection = Strings.Persian ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
            LangBtn.Content = Strings.Persian ? "English" : "فارسی";
            timer.Tick += async (_, _) => await Poll();
            Loaded += async (_, _) => await Start();
            Closing += OnClosing;
        }

        // ---------------------------------------------------------------- start / poll

        async Task Start()
        {
            if (pipe == null) return;
            try
            {
                var hello = await pipe.Call("hello");
                serviceVersion = hello?["version"]?.GetValue<string>() ?? "";
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
            await Poll();
            timer.Start();
        }

        async Task Poll()
        {
            if (pipe == null || polling) return;
            polling = true;
            tick++;
            try
            {
                if (serviceVersion == "")
                {
                    // the service was down: start over once it answers
                    var hello = await pipe.Call("hello", null, 3000);
                    serviceVersion = hello?["version"]?.GetValue<string>() ?? "?";
                    account = await pipe.Call<Account>("account");
                    settings = await pipe.Call<Settings>("settings");
                    ShowAccount();
                    ShowSettings();
                }
                status = await pipe.Call<Status>("status", null, 5000) ?? new Status();
                ServiceOk();
                ShowStatus();
                if (LogPage.Visibility == Visibility.Visible && tick % 3 == 0) await LoadLog();
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

        // ---------------------------------------------------------------- pages

        void Tab_Checked(object sender, RoutedEventArgs e)
        {
            if (HomePage == null) return; // during InitializeComponent
            ShowPage();
        }

        void ShowPage()
        {
            var login = account == null;
            TabBar.Visibility = login ? Visibility.Collapsed : Visibility.Visible;
            LoginPage.Visibility = login ? Visibility.Visible : Visibility.Collapsed;
            HomePage.Visibility = !login && TabHome.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            ServersPage.Visibility = !login && TabServers.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            SettingsPage.Visibility = !login && TabSettings.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            LogPage.Visibility = !login && TabLog.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            if (LogPage.Visibility == Visibility.Visible) _ = LoadLog();
        }

        public void Open(string page)
        {
            switch (page)
            {
                case "servers": TabServers.IsChecked = true; break;
                case "settings": TabSettings.IsChecked = true; break;
                case "log": TabLog.IsChecked = true; break;
                default: TabHome.IsChecked = true; break;
            }
            ShowPage();
        }

        // ---------------------------------------------------------------- home

        Server? Chosen()
        {
            if (account == null || account.Servers.Count == 0) return null;
            var id = status.Server?.Id ?? prefs.LastServer;
            return account.Servers.FirstOrDefault(s => s.Id == id) ?? account.Servers[0];
        }

        internal static string TagsOf(Server s)
        {
            var parts = new List<string> { s.Proto == "amneziawg" ? "AmneziaWG" : "WireGuard" };
            parts.Add(Strings.T(s.Tunnel ? "Through Iran server" : "Direct"));
            if (!string.IsNullOrEmpty(s.City)) parts.Add(s.City!);
            return string.Join("  /  ", parts);
        }

        void ShowStatus()
        {
            var st = status.State;
            var active = st != "disconnected";
            StateText.Text = Strings.T(st switch
            {
                "connecting" => "Connecting…",
                "connected" => "Connected",
                "reconnecting" => "Reconnecting…",
                "error" => "Could not connect",
                _ => "Not connected",
            });
            StateText.Foreground = Brush(st switch
            {
                "connected" => "Green",
                "error" => "Red",
                "connecting" or "reconnecting" => "Gold",
                _ => "Text",
            });
            ActionText.Text = Strings.T(st switch
            {
                "disconnected" => "Connect",
                "connected" => "Disconnect",
                _ => "Cancel",
            });
            Ring.Stroke = Brush(st switch
            {
                "connected" => "Green",
                "error" => "Red",
                "connecting" or "reconnecting" => "Gold",
                _ => "Line",
            });
            Turn(st == "connecting" || st == "reconnecting");

            var srv = Chosen();
            ServerName.Text = srv?.Name ?? Strings.T("Choose a server");
            ServerTags.Text = srv != null ? TagsOf(srv) : "";
            CcText.Text = string.IsNullOrEmpty(srv?.Country) ? "··" : srv!.Country!;

            var on = st == "connected" || st == "reconnecting";
            RxText.Text = on ? Bytes(status.Rx) : "—";
            TxText.Text = on ? Bytes(status.Tx) : "—";
            TimeText.Text = on && status.Since > 0 ? Duration(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - status.Since) : "—";

            ProtectText.Text = active ? Strings.T(status.KillSwitch ? "Kill switch on" : "Kill switch off") : "";
            ErrorText.Text = status.Blocked
                ? Strings.T("Internet is blocked until the VPN is back (kill switch).") + (string.IsNullOrEmpty(status.Error) ? "" : "\n" + status.Error)
                : status.Error ?? "";
            ConnectBtn.IsEnabled = !busy && srv != null;
            if ((active ? status.Server?.Id : null) != shownCurrent) FillServers();
        }

        void Turn(bool on)
        {
            // one moving thing in the whole app: the ring while connecting
            if (on == turning || !SystemParameters.ClientAreaAnimation) return;
            turning = on;
            if (on)
            {
                Ring.StrokeDashArray = new DoubleCollection { 18, 8 };
                RingTurn.BeginAnimation(RotateTransform.AngleProperty,
                    new DoubleAnimation(0, 360, TimeSpan.FromSeconds(2.4)) { RepeatBehavior = RepeatBehavior.Forever });
            }
            else
            {
                Ring.StrokeDashArray = null;
                RingTurn.BeginAnimation(RotateTransform.AngleProperty, null);
            }
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
                if (status.State == "disconnected") await Connect(srv);
                else await pipe.Call("disconnect");
                status = await pipe.Call<Status>("status") ?? new Status();
            }
            catch (Exception ex)
            {
                ErrorText.Text = ex.Message;
            }
            busy = false;
            ShowStatus();
        }

        async Task Connect(Server srv)
        {
            prefs.LastServer = srv.Id;
            prefs.Save();
            await pipe!.Call("connect", new { server = srv.Id });
        }

        void ServerBtn_Click(object sender, RoutedEventArgs e) => Open("servers");

        // ---------------------------------------------------------------- account and servers

        void ShowAccount()
        {
            ShowPage();
            if (account == null) return;
            UserText.Text = account.Username;
            if (account.Expires <= 0) DaysText.Text = Strings.T("Unlimited");
            else
            {
                var days = (account.Expires - DateTimeOffset.UtcNow.ToUnixTimeSeconds()) / 86400.0;
                DaysText.Text = days <= 0 ? Strings.T("Expired") : Math.Ceiling(days).ToString(CultureInfo.InvariantCulture) + " " + Strings.T("days left");
            }
            DataText.Text = account.LimitGb > 0
                ? Math.Max(0, account.LimitGb - account.UsedGb).ToString("0.#", CultureInfo.InvariantCulture) + " " + Strings.T("GB left")
                : Strings.T("Unlimited");
            NoticeText.Text = account.Notice ?? "";
            AccountText.Text = account.Username + (string.IsNullOrEmpty(account.Status) ? "" : "  (" + account.Status + ")");
            FillServers();
            ShowStatus();
        }

        void FillServers()
        {
            if (account == null) return;
            var cur = status.State != "disconnected" ? status.Server?.Id : null;
            shownCurrent = cur;
            ServerList.ItemsSource = account.Servers.Select(s => new ServerItem(s, s.Id == cur)).ToList();
            ServersCount.Text = account.Servers.Count + " " + Strings.T("Servers");
            ServersEmpty.Visibility = account.Servers.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        async void ServerList_Click(object sender, MouseButtonEventArgs e)
        {
            var item = (e.OriginalSource as FrameworkElement)?.DataContext as ServerItem;
            if (item != null) await Pick(item);
        }

        async void ServerList_KeyDown(object sender, KeyEventArgs e)
        {
            if ((e.Key == Key.Enter || e.Key == Key.Space) && ServerList.SelectedItem is ServerItem item) await Pick(item);
        }

        async Task Pick(ServerItem item)
        {
            Open("home");
            if (pipe == null) return;
            try
            {
                await Connect(item.Server);
                status = await pipe.Call<Status>("status") ?? new Status();
            }
            catch (Exception ex)
            {
                ErrorText.Text = ex.Message;
            }
            ShowStatus();
            FillServers();
        }

        async void Refresh_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            ServersCount.Text = Strings.T("Loading…");
            try
            {
                account = await pipe.Call<Account>("refresh") ?? account;
                ServersCount.Text = Strings.T("Updated");
            }
            catch (Exception ex)
            {
                ServersCount.Text = ex.Message;
            }
            ShowAccount();
        }

        async void AddSub_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null || string.IsNullOrWhiteSpace(SubInput.Text)) return;
            AddSubBtn.IsEnabled = false;
            LoginError.Foreground = Brush("Muted");
            LoginError.Text = Strings.T("Loading…");
            try
            {
                account = await pipe.Call<Account>("set_sub", new { input = SubInput.Text.Trim() });
                LoginError.Text = "";
                SubInput.Text = "";
                Open("home");
                ShowAccount();
            }
            catch (Exception ex)
            {
                LoginError.Foreground = Brush("Red");
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
                ShowAccount();
                ShowStatus();
            }
            catch (Exception ex)
            {
                SaveMsg.Text = ex.Message;
            }
        }

        // ---------------------------------------------------------------- settings

        void ShowSettings()
        {
            SignInBox.IsChecked = pipe != null && Prefs.OpenAtSignIn;
            VersionText.Text = "Kanki VPN " + typeof(App).Assembly.GetName().Version?.ToString(3)
                + (serviceVersion != "" ? "   " + Strings.T("Service") + " " + serviceVersion : "");
            if (settings == null) return;
            KillSwitchBox.IsChecked = settings.KillSwitch;
            Ipv6Box.IsChecked = settings.BlockIPv6;
            LanBox.IsChecked = settings.AllowLan;
            AutoBox.IsChecked = settings.AutoConnect;
            SplitAll.IsChecked = settings.SplitMode == "all";
            SplitExclude.IsChecked = settings.SplitMode == "exclude";
            SplitInclude.IsChecked = settings.SplitMode == "include";
            SplitBox.Text = string.Join(Environment.NewLine, settings.SplitList);
            DnsBox.Text = string.Join(", ", settings.Dns);
            MtuBox.Text = settings.Mtu.ToString(CultureInfo.InvariantCulture);
            Split_Checked(this, new RoutedEventArgs());
        }

        void Split_Checked(object sender, RoutedEventArgs e)
        {
            if (SplitBox == null) return;
            SplitBox.IsEnabled = SplitAll.IsChecked != true;
            SplitNote.Visibility = SplitInclude.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        }

        async void Save_Click(object sender, RoutedEventArgs e)
        {
            if (pipe == null) return;
            int.TryParse(MtuBox.Text.Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out var mtu);
            var s = new Settings
            {
                KillSwitch = KillSwitchBox.IsChecked == true,
                BlockIPv6 = Ipv6Box.IsChecked == true,
                AllowLan = LanBox.IsChecked == true,
                AutoConnect = AutoBox.IsChecked == true,
                SplitMode = SplitInclude.IsChecked == true ? "include" : SplitExclude.IsChecked == true ? "exclude" : "all",
                SplitList = SplitBox.Text.Split(new[] { '\r', '\n', ',', ' ' }, StringSplitOptions.RemoveEmptyEntries).ToList(),
                Dns = DnsBox.Text.Split(new[] { ',', ' ', ';' }, StringSplitOptions.RemoveEmptyEntries).ToList(),
                Mtu = mtu,
            };
            SaveBtn.IsEnabled = false;
            try
            {
                settings = await pipe.Call<Settings>("set_settings", new { settings = s });
                SaveMsg.Foreground = Brush("Green");
                SaveMsg.Text = Strings.T("Saved");
                ShowSettings();
            }
            catch (Exception ex)
            {
                SaveMsg.Foreground = Brush("Red");
                SaveMsg.Text = ex.Message;
            }
            SaveBtn.IsEnabled = true;
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
            prefs.Lang = Strings.Persian ? "en" : "fa";
            prefs.Save();
            Strings.Persian = prefs.Lang == "fa";
            ((App)Application.Current).Rebuild(TabSettings.IsChecked == true ? "settings" : "home");
        }

        // ---------------------------------------------------------------- log

        async Task LoadLog()
        {
            if (pipe == null) return;
            try
            {
                var lines = await pipe.Call<List<string>>("logs") ?? new List<string>();
                var text = string.Join(Environment.NewLine, lines);
                if (text != LogBox.Text)
                {
                    var atEnd = LogBox.VerticalOffset + LogBox.ViewportHeight >= LogBox.ExtentHeight - 4;
                    LogBox.Text = text;
                    if (atEnd) LogBox.ScrollToEnd();
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
                LogMsg.Text = Strings.T("Copied");
            }
            catch (Exception ex)
            {
                LogMsg.Text = ex.Message;
            }
        }

        // ---------------------------------------------------------------- window

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
            base.OnClosed(e);
        }

        // ---------------------------------------------------------------- helpers

        static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

        static string Bytes(ulong b)
        {
            string[] units = { "B", "KB", "MB", "GB", "TB" };
            double v = b;
            var i = 0;
            while (v >= 1024 && i < units.Length - 1)
            {
                v /= 1024;
                i++;
            }
            return v.ToString(i == 0 ? "0" : "0.0", CultureInfo.InvariantCulture) + " " + units[i];
        }

        static string Duration(long seconds)
        {
            var t = TimeSpan.FromSeconds(Math.Max(0, seconds));
            return t.TotalHours >= 1
                ? ((int)t.TotalHours).ToString(CultureInfo.InvariantCulture) + t.ToString(@"\:mm\:ss", CultureInfo.InvariantCulture)
                : t.ToString(@"mm\:ss", CultureInfo.InvariantCulture);
        }

        // ---------------------------------------------------------------- screenshots (CI)

        /// <summary>Fills the window with sample data, for the screenshots CI takes.</summary>
        public void Demo(string page)
        {
            account = new Account
            {
                Username = "ali.rezaei", Status = "Active", LimitGb = 50, UsedGb = 12.5,
                Expires = DateTimeOffset.UtcNow.AddDays(23).ToUnixTimeSeconds(),
                Servers = new List<Server>
                {
                    new() { Id = "local/amneziawg", Name = "GERMANY-GAMING", Proto = "amneziawg", Country = "DE", City = "Frankfurt" },
                    new() { Id = "local/wireguard", Name = "GERMANY-GAMING", Proto = "wireguard", Country = "DE", City = "Frankfurt" },
                    new() { Id = "n3@tunnel/amneziawg", Name = "FINLAND", Proto = "amneziawg", Country = "FI", City = "Helsinki", Tunnel = true },
                    new() { Id = "n4/amneziawg", Name = "NETHERLANDS", Proto = "amneziawg", Country = "NL", City = "Amsterdam" },
                    new() { Id = "n5@tunnel/wireguard", Name = "ENGLAND", Proto = "wireguard", Country = "GB", City = "London", Tunnel = true },
                },
            };
            settings = new Settings { SplitMode = "exclude", SplitList = new List<string> { "5.0.0.0/8", "91.108.4.0/22" } };
            status = new Status
            {
                State = "connected", Server = account.Servers[0], Rx = 734_003_200, Tx = 52_428_800, KillSwitch = true,
                Since = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 3725,
            };
            serviceVersion = "1.0.0";
            ShowAccount();
            ShowSettings();
            LogBox.Text = "2026-10-30 09:12:01 Kanki VPN service 1.0.0 starting\n2026-10-30 09:12:04 tunnel up: GERMANY-GAMING (amneziawg) via 203.0.113.9:51820, 32 routes, kill switch true\n2026-10-30 09:12:05 connected (first handshake)";
            if (page == "login") account = null;
            Open(page);
            ShowStatus();
        }
    }
}
