using System;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace KankiVPN
{
    public partial class App : Application
    {
        const string MutexName = @"Local\KankiVPN.UI";
        const string ShowEvent = @"Local\KankiVPN.Show";

        public static Tray? Tray { get; private set; }

        Mutex? single;
        PipeClient? pipe;
        Prefs prefs = new();
        MainWindow? window;

        /// <summary>Where a crash is written: %AppData%\KankiVPN\crash.log (in screenshot mode, that folder).</summary>
        static string crashLog = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "KankiVPN", "crash.log");

        static void Crash(object? error)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(crashLog)!);
                File.AppendAllText(crashLog, DateTime.Now.ToString("u") + " " + error + Environment.NewLine);
            }
            catch
            {
                // nothing more to do
            }
        }

        protected override async void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);
            AppDomain.CurrentDomain.UnhandledException += (_, x) => Crash(x.ExceptionObject);
            DispatcherUnhandledException += (_, x) => Crash(x.Exception);
            var args = e.Args;
            var shot = Array.IndexOf(args, "--shot");
            if (shot >= 0 && shot + 1 < args.Length)
            {
                crashLog = Path.Combine(args[shot + 1], "crash.log");
                try
                {
                    await Shots(args[shot + 1]);
                }
                catch (Exception ex)
                {
                    Crash(ex);
                }
                Shutdown();
                return;
            }

            // one copy only: a second start just shows the first one's window
            single = new Mutex(true, MutexName, out var first);
            if (!first)
            {
                try
                {
                    EventWaitHandle.OpenExisting(ShowEvent).Set();
                }
                catch
                {
                    // the other copy is closing; nothing to show
                }
                Shutdown();
                return;
            }
            var show = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEvent);
            new Thread(() =>
            {
                while (show.WaitOne()) Dispatcher.BeginInvoke(new Action(ShowMain));
            }) { IsBackground = true }.Start();

            prefs = Prefs.Load();
            Strings.Persian = prefs.Lang != "en";
            pipe = new PipeClient();
            Tray = new Tray(ShowMain, Quit, Toggle);
            window = new MainWindow(pipe, prefs);
            if (!args.Contains("--tray")) window.Show();
        }

        void ShowMain()
        {
            if (window == null) return;
            window.Show();
            if (window.WindowState == WindowState.Minimized) window.WindowState = WindowState.Normal;
            window.Activate();
        }

        /// <summary>Builds the window again (after the language changed).</summary>
        public void Rebuild(string page)
        {
            var old = window;
            window = new MainWindow(pipe, prefs);
            if (old != null)
            {
                window.Left = old.Left;
                window.Top = old.Top;
                window.Width = old.Width;
                window.Height = old.Height;
                window.WindowStartupLocation = WindowStartupLocation.Manual;
                old.Quitting = true;
                old.Close();
            }
            window.Show();
            window.Open(page);
            Tray?.Relabel();
        }

        async void Toggle()
        {
            if (pipe == null) return;
            try
            {
                var st = await pipe.Call<Status>("status");
                if (st != null && st.State != "disconnected") await pipe.Call("disconnect");
                else if (!string.IsNullOrEmpty(prefs.LastServer)) await pipe.Call("connect", new { server = prefs.LastServer });
                else ShowMain();
            }
            catch
            {
                ShowMain(); // the window explains what is wrong
            }
        }

        void Quit()
        {
            // the app goes, the VPN stays: it belongs to the service
            if (window != null)
            {
                window.Quitting = true;
                window.Close();
            }
            Tray?.Dispose();
            pipe?.Dispose();
            Shutdown();
        }

        /// <summary>Persian text in each font and mode, to see which one shapes right.</summary>
        async Task FontCheck(string file)
        {
            var panel = new System.Windows.Controls.StackPanel { Background = Brushes.Black, Width = 400 };
            void Add(string label, System.Windows.Media.FontFamily f, TextFormattingMode mode)
            {
                var t = new System.Windows.Controls.TextBlock
                {
                    Text = label + ": وصل است کانکی",
                    FontFamily = f, FontSize = 20, Foreground = Brushes.White, Margin = new Thickness(8),
                    FlowDirection = FlowDirection.RightToLeft,
                };
                TextOptions.SetTextFormattingMode(t, mode);
                panel.Children.Add(t);
            }
            var vazir = (System.Windows.Media.FontFamily)Resources["Vazir"];
            Add("1", vazir, TextFormattingMode.Display);
            Add("2", vazir, TextFormattingMode.Ideal);
            Add("3", new System.Windows.Media.FontFamily("Segoe UI"), TextFormattingMode.Display);
            Add("4", new System.Windows.Media.FontFamily("Tahoma"), TextFormattingMode.Ideal);
            var w = new Window { Content = panel, SizeToContent = SizeToContent.WidthAndHeight, Left = -30000, Top = -30000, ShowInTaskbar = false };
            w.Show();
            await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
            var bmp = new RenderTargetBitmap((int)panel.ActualWidth, (int)panel.ActualHeight, 96, 96, PixelFormats.Pbgra32);
            bmp.Render(panel);
            var enc = new PngBitmapEncoder();
            enc.Frames.Add(BitmapFrame.Create(bmp));
            using (var f = File.Create(file))
                enc.Save(f);
            w.Close();
        }

        /// <summary>--shot DIR: saves each page in both languages as PNG with sample data.</summary>
        async Task Shots(string dir)
        {
            Directory.CreateDirectory(dir);
            await FontCheck(Path.Combine(dir, "fonts.png"));
            foreach (var lang in new[] { "fa", "en" })
            {
                Strings.Persian = lang == "fa";
                foreach (var size in new[] { (400, 720), (900, 760) })
                {
                    foreach (var page in new[] { "login", "home", "servers", "settings", "log" })
                    {
                        var w = new MainWindow(null, new Prefs()) { Width = size.Item1, Height = size.Item2, ShowInTaskbar = false };
                        w.WindowStartupLocation = WindowStartupLocation.Manual;
                        w.Left = -30000;
                        w.Top = -30000;
                        w.Show();
                        w.Demo(page);
                        await Dispatcher.Yield(DispatcherPriority.ApplicationIdle);
                        w.UpdateLayout();
                        var root = (FrameworkElement)w.Content;
                        var bmp = new RenderTargetBitmap((int)root.ActualWidth, (int)root.ActualHeight, 96, 96, PixelFormats.Pbgra32);
                        var bg = new DrawingVisual();
                        using (var dc = bg.RenderOpen())
                            dc.DrawRectangle(Brushes.Black, null, new Rect(0, 0, root.ActualWidth, root.ActualHeight));
                        bmp.Render(bg);
                        bmp.Render(root);
                        var enc = new PngBitmapEncoder();
                        enc.Frames.Add(BitmapFrame.Create(bmp));
                        using (var f = File.Create(Path.Combine(dir, $"{lang}-{size.Item1}-{page}.png")))
                            enc.Save(f);
                        w.Quitting = true;
                        w.Close();
                    }
                }
            }
        }
    }

    /// <summary>The icon next to the clock: open, connect / disconnect, quit.</summary>
    public sealed class Tray : IDisposable
    {
        readonly System.Windows.Forms.NotifyIcon icon;
        readonly Action open, quit, toggle;
        string state = "disconnected";

        public Tray(Action open, Action quit, Action toggle)
        {
            this.open = open;
            this.quit = quit;
            this.toggle = toggle;
            var stream = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/kanki.ico")).Stream;
            icon = new System.Windows.Forms.NotifyIcon
            {
                Icon = new System.Drawing.Icon(stream, System.Windows.Forms.SystemInformation.SmallIconSize),
                Visible = true,
            };
            icon.DoubleClick += (_, _) => open();
            Relabel();
        }

        public void Relabel()
        {
            var menu = new System.Windows.Forms.ContextMenuStrip
            {
                RightToLeft = Strings.Persian ? System.Windows.Forms.RightToLeft.Yes : System.Windows.Forms.RightToLeft.No,
            };
            menu.Items.Add(Strings.T("Open"), null, (_, _) => open());
            menu.Items.Add(Strings.T(state == "disconnected" ? "Connect" : "Disconnect"), null, (_, _) => toggle());
            menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
            menu.Items.Add(Strings.T("Quit"), null, (_, _) => quit());
            var old = icon.ContextMenuStrip;
            icon.ContextMenuStrip = menu;
            old?.Dispose();
            icon.Text = Strings.T("Kanki VPN") + ": " + Strings.T(state switch
            {
                "connected" => "Connected",
                "connecting" => "Connecting…",
                "reconnecting" => "Reconnecting…",
                "error" => "Could not connect",
                _ => "Not connected",
            });
        }

        public void SetState(string s)
        {
            if (s == state) return;
            state = s;
            Relabel();
        }

        public void Hint(string text) => icon.ShowBalloonTip(4000, Strings.T("Kanki VPN"), text, System.Windows.Forms.ToolTipIcon.Info);

        public void Dispose()
        {
            icon.Visible = false;
            icon.Dispose();
        }
    }
}
