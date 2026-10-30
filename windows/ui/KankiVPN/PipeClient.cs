using System;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;

namespace KankiVPN
{
    /// <summary>The service answered with an error (shown to the user as is).</summary>
    public sealed class ServiceException : Exception
    {
        public ServiceException(string message) : base(message) { }
    }

    /// <summary>
    /// Talks to kanki-svc over \\.\pipe\KankiVPN: one JSON line per request and
    /// per answer. Before the first request it checks that the pipe really
    /// belongs to kanki-svc.exe next to this app, so another program cannot
    /// pretend to be the service.
    /// </summary>
    public sealed class PipeClient : IDisposable
    {
        const string PipeName = "KankiVPN";

        readonly SemaphoreSlim gate = new(1, 1);
        NamedPipeClientStream? pipe;
        StreamReader? reader;
        int nextId;

        public async Task<JsonNode?> Call(string cmd, object? args = null, int timeoutMs = 60000)
        {
            await gate.WaitAsync().ConfigureAwait(false);
            try
            {
                for (var attempt = 0; ; attempt++)
                {
                    try
                    {
                        await EnsureOpen().ConfigureAwait(false);
                        return await Send(cmd, args, timeoutMs).ConfigureAwait(false);
                    }
                    catch (Exception e) when (attempt == 0 && e is IOException or ObjectDisposedException)
                    {
                        Close(); // the service restarted: one fresh try
                    }
                }
            }
            finally
            {
                gate.Release();
            }
        }

        async Task EnsureOpen()
        {
            if (pipe is { IsConnected: true }) return;
            Close();
            var p = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            try
            {
                await p.ConnectAsync(1500).ConfigureAwait(false);
            }
            catch (TimeoutException)
            {
                p.Dispose();
                // the service is stopped (it crashed too often, or someone stopped it): start it ourselves
                // (the installer lets normal users start it) and wait for its pipe
                if (!StartService()) throw new IOException("service not running");
                p = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
                try
                {
                    await p.ConnectAsync(10000).ConfigureAwait(false);
                }
                catch (TimeoutException)
                {
                    p.Dispose();
                    throw new IOException("service not running");
                }
            }
            if (!Trusted(p.SafePipeHandle))
            {
                p.Dispose();
                throw new ServiceException(Strings.T("The VPN service could not be verified. Reinstall Kanki VPN."));
            }
            pipe = p;
            reader = new StreamReader(p, new UTF8Encoding(false));
        }

        async Task<JsonNode?> Send(string cmd, object? args, int timeoutMs)
        {
            var id = ++nextId;
            var req = new JsonObject { ["id"] = id, ["cmd"] = cmd };
            if (args != null) req["args"] = JsonSerializer.SerializeToNode(args);
            var line = Encoding.UTF8.GetBytes(req.ToJsonString() + "\n");
            using var cts = new CancellationTokenSource(timeoutMs);
            await pipe!.WriteAsync(line, cts.Token).ConfigureAwait(false);
            await pipe.FlushAsync(cts.Token).ConfigureAwait(false);
            while (true)
            {
                var text = await reader!.ReadLineAsync(cts.Token).ConfigureAwait(false)
                    ?? throw new IOException("service closed the connection");
                var resp = JsonNode.Parse(text);
                if (resp?["id"]?.GetValue<int>() != id) continue;
                if (resp["ok"]?.GetValue<bool>() != true)
                    throw new ServiceException(resp["error"]?.GetValue<string>() ?? "error");
                return resp["data"];
            }
        }

        public async Task<T?> Call<T>(string cmd, object? args = null, int timeoutMs = 60000) where T : class
        {
            var node = await Call(cmd, args, timeoutMs).ConfigureAwait(false);
            return node?.Deserialize<T>();
        }

        void Close()
        {
            reader?.Dispose();
            pipe?.Dispose();
            reader = null;
            pipe = null;
        }

        public void Dispose() => Close();

        // ---- start a stopped service

        static bool StartService()
        {
            var scm = OpenSCManager(null, null, ScConnect);
            if (scm == IntPtr.Zero) return false;
            try
            {
                var svc = OpenServiceW(scm, "KankiVPN", ServiceStart);
                if (svc == IntPtr.Zero) return false;
                try
                {
                    // 1056 = already running (its pipe is just slow)
                    return StartServiceW(svc, 0, IntPtr.Zero) || Marshal.GetLastWin32Error() == 1056;
                }
                finally
                {
                    CloseServiceHandle(svc);
                }
            }
            finally
            {
                CloseServiceHandle(scm);
            }
        }

        const uint ScConnect = 0x0001, ServiceStart = 0x0010;

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "OpenSCManagerW")]
        static extern IntPtr OpenSCManager(string? machine, string? db, uint access);

        [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern IntPtr OpenServiceW(IntPtr scm, string name, uint access);

        [DllImport("advapi32.dll", SetLastError = true)]
        static extern bool StartServiceW(IntPtr svc, uint argc, IntPtr argv);

        [DllImport("advapi32.dll")]
        static extern bool CloseServiceHandle(IntPtr h);

        // ---- who is on the other end of the pipe

        static bool Trusted(SafePipeHandle h)
        {
            if (!GetNamedPipeServerProcessId(h, out var pid)) return false;
            // kanki-svc.exe کنار همین برنامه در پوشه‌ی نصب است. از ProcessPath گرفته می‌شود نه
            // AppContext.BaseDirectory: برنامه تک‌فایل است و BaseDirectory به پوشه‌ی موقتِ بازشدن
            // کتابخانه‌های بومی اشاره می‌کند، نه پوشه‌ی نصب.
            var appDir = Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
            var expected = Path.GetFullPath(Path.Combine(appDir, "kanki-svc.exe"));
            var image = Win32Image(pid);
            if (image != null)
                return string.Equals(Path.GetFullPath(image), expected, StringComparison.OrdinalIgnoreCase);
            // کاربر عادی (حتی ادمینِ بدون «Run as administrator») اجازه‌ی باز کردن پروسه‌ی SYSTEM را ندارد؛
            // پس OpenProcess رد می‌شد و «سرویس تأیید نشد» می‌آمد. مسیر را بدون باز کردن پروسه از
            // ویندوز می‌گیریم (همان راهی که Task Manager می‌رود) و با مسیر دستگاهیِ پوشه‌ی نصب مقایسه می‌کنیم.
            var nt = NtImage(pid);
            var want = NtPath(expected);
            return nt != null && want != null && string.Equals(nt, want, StringComparison.OrdinalIgnoreCase);
        }

        static string? Win32Image(uint pid)
        {
            var proc = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (proc == IntPtr.Zero) return null;
            try
            {
                var sb = new StringBuilder(1024);
                var size = sb.Capacity;
                return QueryFullProcessImageName(proc, 0, sb, ref size) ? sb.ToString() : null;
            }
            finally
            {
                CloseHandle(proc);
            }
        }

        // \Device\HarddiskVolume3\Program Files\Kanki VPN\kanki-svc.exe
        static string? NtImage(uint pid)
        {
            const int SystemProcessIdInformation = 88;
            const ushort max = 4096;
            var buf = Marshal.AllocHGlobal(max);
            try
            {
                var info = new SystemProcessIdInfo
                {
                    ProcessId = (IntPtr)pid,
                    ImageName = new UnicodeString { Length = 0, MaximumLength = max, Buffer = buf },
                };
                if (NtQuerySystemInformation(SystemProcessIdInformation, ref info, Marshal.SizeOf<SystemProcessIdInfo>(), out _) != 0)
                    return null;
                return Marshal.PtrToStringUni(info.ImageName.Buffer, info.ImageName.Length / 2);
            }
            finally
            {
                Marshal.FreeHGlobal(buf);
            }
        }

        // C:\x\y → \Device\HarddiskVolume3\x\y
        static string? NtPath(string win32)
        {
            var root = Path.GetPathRoot(win32);
            if (root is not { Length: >= 2 } || root[1] != ':') return null;
            var sb = new StringBuilder(1024);
            if (QueryDosDevice(root.Substring(0, 2), sb, sb.Capacity) == 0) return null;
            return sb.ToString() + win32.Substring(2);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct UnicodeString
        {
            public ushort Length;
            public ushort MaximumLength;
            public IntPtr Buffer;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct SystemProcessIdInfo
        {
            public IntPtr ProcessId;
            public UnicodeString ImageName;
        }

        [DllImport("ntdll.dll")]
        static extern int NtQuerySystemInformation(int infoClass, ref SystemProcessIdInfo info, int length, out int returned);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "QueryDosDeviceW")]
        static extern uint QueryDosDevice(string device, StringBuilder target, int max);

        const uint ProcessQueryLimitedInformation = 0x1000;

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint serverProcessId);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "QueryFullProcessImageNameW")]
        static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder name, ref int size);

        [DllImport("kernel32.dll")]
        static extern bool CloseHandle(IntPtr h);
    }
}
