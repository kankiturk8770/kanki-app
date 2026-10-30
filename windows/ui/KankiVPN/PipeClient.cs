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
                throw new IOException("service not running");
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

        // ---- who is on the other end of the pipe

        static bool Trusted(SafePipeHandle h)
        {
            if (!GetNamedPipeServerProcessId(h, out var pid)) return false;
            var proc = OpenProcess(ProcessQueryLimitedInformation, false, pid);
            if (proc == IntPtr.Zero) return false;
            try
            {
                var sb = new StringBuilder(1024);
                var size = sb.Capacity;
                if (!QueryFullProcessImageName(proc, 0, sb, ref size)) return false;
                var expected = Path.Combine(AppContext.BaseDirectory, "kanki-svc.exe");
                return string.Equals(Path.GetFullPath(sb.ToString()), Path.GetFullPath(expected), StringComparison.OrdinalIgnoreCase);
            }
            finally
            {
                CloseHandle(proc);
            }
        }

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
