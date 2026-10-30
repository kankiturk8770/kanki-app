using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;

namespace KankiVPN
{
    /// <summary>Speed test against Cloudflare, like the Android app (Speed.kt): latency and jitter
    /// first, then download and upload with parallel streams. It runs inside the tunnel when the VPN is on.</summary>
    public sealed class Speed
    {
        const string Base = "https://speed.cloudflare.com";
        static readonly HttpClient Http = new(new SocketsHttpHandler { UseProxy = false, PooledConnectionLifetime = TimeSpan.FromMinutes(1) })
        {
            Timeout = TimeSpan.FromSeconds(30),
        };

        public long UsedBytes;

        static string NoCache() => DateTime.UtcNow.Ticks.ToString() + Random.Shared.Next(1000, 9999);

        /// <summary>(ping ms, jitter ms, lost samples of n)</summary>
        public async Task<(long ping, long jitter, int lost)> Latency(int n, CancellationToken ct)
        {
            var samples = new List<double>();
            var lost = 0;
            for (var i = 0; i < n; i++)
            {
                var sw = Stopwatch.StartNew();
                try
                {
                    using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    cts.CancelAfter(4000);
                    using var r = await Http.GetAsync($"{Base}/__down?bytes=0&r={NoCache()}", HttpCompletionOption.ResponseHeadersRead, cts.Token);
                    samples.Add(sw.Elapsed.TotalMilliseconds);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch
                {
                    lost++;
                }
            }
            if (samples.Count == 0) return (-1, -1, lost);
            // the first answer opens the connection; the rest are the real round trips
            var s = samples.Count > 2 ? samples.Skip(1).ToList() : samples;
            var jitter = s.Count > 1 ? s.Zip(s.Skip(1), (a, b) => Math.Abs(a - b)).Average() : 0;
            return ((long)Math.Max(1, s.Min()), (long)Math.Round(jitter), lost);
        }

        /// <summary>Mbps; onProgress gets the running value.</summary>
        public Task<double> Download(double seconds, int streams, Action<double> onProgress, CancellationToken ct) =>
            Measure(seconds, streams, onProgress, ct, async (count, token) =>
            {
                using var r = await Http.GetAsync($"{Base}/__down?bytes=50000000&r={NoCache()}", HttpCompletionOption.ResponseHeadersRead, token);
                await using var s = await r.Content.ReadAsStreamAsync(token);
                var buf = new byte[65536];
                int n;
                while ((n = await s.ReadAsync(buf, token)) > 0) count(n);
            });

        public Task<double> Upload(double seconds, int streams, Action<double> onProgress, CancellationToken ct)
        {
            var payload = new byte[1 << 20];
            Random.Shared.NextBytes(payload);
            return Measure(seconds, streams, onProgress, ct, async (count, token) =>
            {
                using var content = new ByteArrayContent(payload);
                using var r = await Http.PostAsync($"{Base}/__up?r={NoCache()}", content, token);
                count(payload.Length);
            });
        }

        async Task<double> Measure(double seconds, int streams, Action<double> onProgress, CancellationToken ct,
            Func<Action<int>, CancellationToken, Task> work)
        {
            long bytes = 0;
            var sw = Stopwatch.StartNew();
            using var stop = CancellationTokenSource.CreateLinkedTokenSource(ct);
            stop.CancelAfter(TimeSpan.FromSeconds(seconds));
            void Count(int n) => Interlocked.Add(ref bytes, n);
            var tasks = Enumerable.Range(0, streams).Select(_ => Task.Run(async () =>
            {
                while (!stop.IsCancellationRequested)
                {
                    try
                    {
                        await work(Count, stop.Token);
                    }
                    catch
                    {
                        if (!stop.IsCancellationRequested) await Task.Delay(200);
                    }
                }
            })).ToList();
            while (!stop.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(250, stop.Token);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
                onProgress(Mbps(Interlocked.Read(ref bytes), sw.Elapsed.TotalSeconds));
            }
            await Task.WhenAll(tasks);
            ct.ThrowIfCancellationRequested();
            var total = Interlocked.Read(ref bytes);
            UsedBytes += total;
            return Mbps(total, sw.Elapsed.TotalSeconds);
        }

        static double Mbps(long bytes, double secs) => secs <= 0 ? 0 : bytes * 8 / secs / 1_000_000;
    }
}
