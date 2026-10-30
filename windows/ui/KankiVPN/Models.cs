using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace KankiVPN
{
    // The service's JSON (windows/service/internal/core, sub, state).

    public sealed class Server
    {
        [JsonPropertyName("id")] public string Id { get; set; } = "";
        [JsonPropertyName("node")] public string Node { get; set; } = "";
        [JsonPropertyName("name")] public string Name { get; set; } = "";
        [JsonPropertyName("proto")] public string Proto { get; set; } = "";
        [JsonPropertyName("cc")] public string? Country { get; set; }
        [JsonPropertyName("city")] public string? City { get; set; }
        [JsonPropertyName("tunnel")] public bool Tunnel { get; set; }
        [JsonPropertyName("lat")] public double Lat { get; set; }
        [JsonPropertyName("lon")] public double Lon { get; set; }
    }

    public sealed class Account
    {
        [JsonPropertyName("username")] public string Username { get; set; } = "";
        [JsonPropertyName("status")] public string Status { get; set; } = "";
        [JsonPropertyName("expires")] public long Expires { get; set; }
        [JsonPropertyName("limit_gb")] public double LimitGb { get; set; }
        [JsonPropertyName("used_gb")] public double UsedGb { get; set; }
        [JsonPropertyName("notice")] public string? Notice { get; set; }
        [JsonPropertyName("support")] public string? Support { get; set; }
        [JsonPropertyName("max_conn")] public int MaxConn { get; set; }
        // Go sends an empty list as null
        [JsonPropertyName("servers")] public List<Server> Servers { get => servers; set => servers = value ?? new(); }
        List<Server> servers = new();
    }

    public sealed class Status
    {
        [JsonPropertyName("state")] public string State { get; set; } = "disconnected";
        [JsonPropertyName("server")] public Server? Server { get; set; }
        [JsonPropertyName("since")] public long Since { get; set; }
        [JsonPropertyName("rx")] public ulong Rx { get; set; }
        [JsonPropertyName("tx")] public ulong Tx { get; set; }
        [JsonPropertyName("handshake")] public long Handshake { get; set; }
        [JsonPropertyName("error")] public string? Error { get; set; }
        [JsonPropertyName("blocked")] public bool Blocked { get; set; }
        [JsonPropertyName("kill_switch")] public bool KillSwitch { get; set; }
        [JsonPropertyName("endpoint")] public string? Endpoint { get; set; }
    }

    public sealed class Settings
    {
        [JsonPropertyName("kill_switch")] public bool KillSwitch { get; set; } = true;
        [JsonPropertyName("block_ipv6")] public bool BlockIPv6 { get; set; } = true;
        [JsonPropertyName("allow_lan")] public bool AllowLan { get; set; } = true;
        [JsonPropertyName("split_mode")] public string SplitMode { get; set; } = "all";
        // Go sends an empty list as null
        [JsonPropertyName("split_list")] public List<string> SplitList { get => splitList; set => splitList = value ?? new(); }
        List<string> splitList = new();
        [JsonPropertyName("dns")] public List<string> Dns { get => dns; set => dns = value ?? new(); }
        List<string> dns = new();
        [JsonPropertyName("auto_connect")] public bool AutoConnect { get; set; }
        [JsonPropertyName("mtu")] public int Mtu { get; set; }
    }
}
