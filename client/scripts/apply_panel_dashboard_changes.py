# -*- coding: utf-8 -*-
from __future__ import annotations
import pathlib
import textwrap

ROOT = pathlib.Path(r"D:\Project\ms\ServerMaintance")
SERVICES = ROOT / "deploy" / "cloud-monitor-dotnet" / "CloudMonitor.Panel" / "CloudMonitor.Panel" / "Services"

def write_utf8(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")

def patch_windows_collector() -> None:
    path = SERVICES / "WindowsCollectorService.cs"
    text = path.read_text(encoding="utf-8")
    if "_lastNetSample" not in text:
        text = text.replace(
            "    private double _lastCpuPct;\n",
            "    private double _lastCpuPct;\n"
            "    private DateTime _lastNetSample = DateTime.MinValue;\n"
            "    private Dictionary<string, (long rx, long tx)>? _prevNet;\n",
        )
    old_net = """    private static object NetSnapshot()
    {
        long rx = 0, tx = 0;
        foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
        {
            var s = n.GetIPv4Statistics();
            rx += s.BytesReceived;
            tx += s.BytesSent;
        }
        return new { rx, tx };
    }
"""
    new_net = textwrap.dedent("""\
    private object NetSnapshot()
    {
        var now = DateTime.UtcNow;
        var netCur = new Dictionary<string, (long rx, long tx)>();
        foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
        {
            try
            {
                var stats = n.GetIPStatistics();
                netCur[n.Name] = (stats.BytesReceived, stats.BytesSent);
            }
            catch { /* skip */ }
        }

        var dt = _lastNetSample == DateTime.MinValue ? 0.0 : (now - _lastNetSample).TotalSeconds;
        var ifaces = new Dictionary<string, object>();
        foreach (var (name, cur) in netCur)
        {
            double rxBps = 0, txBps = 0;
            if (_prevNet != null && _prevNet.TryGetValue(name, out var prev) && dt > 0)
            {
                var drx = cur.rx - prev.rx;
                var dtx = cur.tx - prev.tx;
                if (drx >= 0) rxBps = drx / dt;
                if (dtx >= 0) txBps = dtx / dt;
            }
            ifaces[name] = new
            {
                rxBps = Math.Round(rxBps, 1),
                txBps = Math.Round(txBps, 1),
                rxTotal = cur.rx,
                txTotal = cur.tx,
            };
        }

        _prevNet = netCur;
        _lastNetSample = now;
        return new { ifaces };
    }
""")
    if old_net not in text:
        raise SystemExit("NetSnapshot block not found")
    text = text.replace(old_net, new_net)
    old_disks = """    public object Disks()
    {
        var mounts = new List<object>();
        foreach (var d in DriveInfo.GetDrives().Where(x => x.IsReady))
        {
            var used = d.TotalSize - d.AvailableFreeSpace;
            var pct = d.TotalSize > 0 ? Math.Round(100.0 * used / d.TotalSize, 1) : 0;
            mounts.Add(new
            {
                mount = d.Name.TrimEnd('\\\\'),
"""
    new_disks = """    public object Disks()
    {
        var mounts = new List<object>();
        var sysRoot = Path.GetPathRoot(Environment.SystemDirectory) ?? "C:\\\\";
        foreach (var d in DriveInfo.GetDrives().Where(x => x.IsReady))
        {
            var used = d.TotalSize - d.AvailableFreeSpace;
            var pct = d.TotalSize > 0 ? Math.Round(100.0 * used / d.TotalSize, 1) : 0;
            var mount = string.Equals(d.Name, sysRoot, StringComparison.OrdinalIgnoreCase)
                ? "/"
                : d.Name.TrimEnd('\\\\');
            mounts.Add(new
            {
                mount,
"""
    if old_disks not in text:
        raise SystemExit("Disks block not found")
    text = text.replace(old_disks, new_disks)
    write_utf8(path, text)
    print("patched WindowsCollectorService.cs")

def create_panel_dashboard_history() -> None:
    path = SERVICES / "PanelDashboardHistory.cs"
    write_utf8(path, PANEL_DASHBOARD_HISTORY_CS)
    print("wrote PanelDashboardHistory.cs")

PANEL_DASHBOARD_HISTORY_CS = r'''using System.Text.Json;

namespace CloudMonitor.Panel.Services;

public sealed class PanelDashboardHistory
{
    private readonly object _gate = new();
    private readonly List<CompactPoint> _histRecent = new();
    private readonly List<CompactPoint> _histLong = new();
    private long _lastLongSample;
    private CompactPoint? _lastCompact;
    private double? _rootDiskPct;

    public void SetRootDiskPct(double? pct) => _rootDiskPct = pct;

    public void Record(JsonElement metrics)
    {
        var compact = CompactSample(metrics);
        _lastCompact = compact;
        lock (_gate)
        {
            _histRecent.Add(compact);
            while (_histRecent.Count > 600)
                _histRecent.RemoveAt(0);

            var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (nowMs - _lastLongSample >= 30000)
            {
                _lastLongSample = nowMs;
                _histLong.Add(compact);
                while (_histLong.Count > 3000)
                    _histLong.RemoveAt(0);
            }
        }
    }

    public object GetHistory(string? range)
    {
        var rangeKey = string.IsNullOrWhiteSpace(range) ? "10m" : range!;
        var secs = RangeSeconds(rangeKey);
        var cutoff = DateTimeOffset.UtcNow.ToUnixTimeSeconds() - secs;
        List<CompactPoint> points;
        lock (_gate)
        {
            points = (secs <= 900 ? _histRecent : _histLong)
                .Where(p => p.t >= cutoff)
                .Select(ClonePoint)
                .ToList();
        }
        if (_lastCompact != null && (points.Count == 0 || points[^1].t < _lastCompact.t))
            points.Add(ClonePoint(_lastCompact));
        return new { range = rangeKey, points };
    }

    private CompactPoint CompactSample(JsonElement m)
    {
        var ts = m.TryGetProperty("ts", out var tsEl) && tsEl.TryGetInt64(out var tsVal)
            ? tsVal
            : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var cpuPct = 0.0;
        if (m.TryGetProperty("cpu", out var cpu) && cpu.TryGetProperty("totalPct", out var tp))
            cpuPct = tp.GetDouble();
        var memPct = 0.0;
        var swapPct = 0.0;
        if (m.TryGetProperty("memory", out var mem))
        {
            if (mem.TryGetProperty("actualUsedPct", out var ap))
                memPct = ap.GetDouble();
            if (mem.TryGetProperty("swapPct", out var sp))
                swapPct = sp.GetDouble();
        }

        double rx = 0, tx = 0;
        if (m.TryGetProperty("net", out var net) && net.TryGetProperty("ifaces", out var ifaces))
        {
            foreach (var iface in ifaces.EnumerateObject())
            {
                if (iface.Name == "lo")
                    continue;
                var v = iface.Value;
                if (v.TryGetProperty("rxBps", out var rxEl))
                    rx += rxEl.GetDouble();
                if (v.TryGetProperty("txBps", out var txEl))
                    tx += txEl.GetDouble();
            }
        }

        return new CompactPoint
        {
            t = ts / 1000,
            c = Math.Round(cpuPct, 1),
            m = Math.Round(memPct, 1),
            s = Math.Round(swapPct, 1),
            rx = (long)Math.Round(rx),
            tx = (long)Math.Round(tx),
            d = _rootDiskPct ?? 0,
        };
    }

    private static int RangeSeconds(string range) => range switch
    {
        "1h" => 3600,
        "6h" => 21600,
        "24h" => 86400,
        _ => 600,
    };

    private static CompactPoint ClonePoint(CompactPoint p) => new()
    {
        t = p.t,
        c = p.c,
        m = p.m,
        s = p.s,
        rx = p.rx,
        tx = p.tx,
        d = p.d,
    };

    private sealed class CompactPoint
    {
        public long t { get; set; }
        public double c { get; set; }
        public double m { get; set; }
        public double s { get; set; }
        public long rx { get; set; }
        public long tx { get; set; }
        public double d { get; set; }
    }
}
'''

def patch_panel_websocket_hub() -> None:
    path = SERVICES / "PanelWebSocketHub.cs"
    text = path.read_text(encoding="utf-8")
    if "using System.Diagnostics;" not in text:
        text = "using System.Diagnostics;\n" + text
    if "PanelDashboardHistory" not in text:
        text = text.replace(
            "    private readonly MetricsStoreService _metricsStore;\n",
            "    private readonly MetricsStoreService _metricsStore;\n    private readonly PanelDashboardHistory _dashHistory;\n",
        )
        text = text.replace(
            "    private const int HistoryMax = 150;\n",
            "    private const int HistoryMax = 150;\n    private int _tickCount;\n",
        )
        text = text.replace(
            "    public PanelWebSocketHub(WindowsCollectorService col, PanelConfigService cfgSvc, PanelAuthService auth, PanelShellSessionManager shells, MetricsStoreService metricsStore)\n",
            "    public PanelWebSocketHub(WindowsCollectorService col, PanelConfigService cfgSvc, PanelAuthService auth, PanelShellSessionManager shells, MetricsStoreService metricsStore, PanelDashboardHistory dashHistory)\n",
        )
        text = text.replace(
            "        _metricsStore = metricsStore;\n    }",
            "        _metricsStore = metricsStore;\n        _dashHistory = dashHistory;\n    }",
        )
    old = """    private async Task BroadcastMetricsAsync(CancellationToken ct)
    {
        object point;
        try { point = _col.Metrics(); }
        catch { return; }
        _metricsStore.TryRecord(point, _col);
        lock (_history)
        {
            _history.Add(point);
            if (_history.Count > HistoryMax) _history.RemoveAt(0);
        }
        var msg = JsonSerializer.Serialize(new { type = \"metrics\", data = point });
        var bytes = Encoding.UTF8.GetBytes(msg);
"""
    new = """    private async Task BroadcastMetricsAsync(CancellationToken ct)
    {
        object point;
        try { point = _col.Metrics(); }
        catch { return; }
        _tickCount++;
        object? top = null;
        if (_tickCount % 3 == 0)
        {
            try { top = TopProcessesLite(8); }
            catch { /* ignore */ }
        }
        object? disks = null;
        if (_tickCount % 5 == 0)
        {
            try
            {
                disks = _col.Disks();
                UpdateRootDiskPct(disks);
            }
            catch { /* ignore */ }
        }
        _metricsStore.TryRecord(point, _col);
        try { _dashHistory.Record(JsonSerializer.SerializeToElement(point)); }
        catch { /* ignore */ }
        lock (_history)
        {
            _history.Add(point);
            if (_history.Count > HistoryMax) _history.RemoveAt(0);
        }
        var msg = JsonSerializer.Serialize(new { type = \"metrics\", data = point, top, disks });
        var bytes = Encoding.UTF8.GetBytes(msg);
"""
    if old not in text:
        raise SystemExit("Broadcast block not found")
    text = text.replace(old, new)
    if "TopProcessesLite" not in text:
        helpers = textwrap.dedent("""\

    private void UpdateRootDiskPct(object disks)
    {
        try
        {
            var json = JsonSerializer.SerializeToElement(disks);
            if (!json.TryGetProperty(\"mounts\", out var mounts))
                return;
            double? pct = null;
            foreach (var m in mounts.EnumerateArray())
            {
                if (m.TryGetProperty(\"mount\", out var mountEl) && mountEl.GetString() == \"/\"
                    && m.TryGetProperty(\"pct\", out var pctEl))
                {
                    pct = pctEl.GetDouble();
                    break;
                }
            }
            if (pct == null && mounts.GetArrayLength() > 0)
            {
                var first = mounts[0];
                if (first.TryGetProperty(\"pct\", out var p0))
                    pct = p0.GetDouble();
            }
            if (pct != null)
                _dashHistory.SetRootDiskPct(pct);
        }
        catch { /* ignore */ }
    }

    private static List<object> TopProcessesLite(int limit = 8)
    {
        var list = new List<object>();
        foreach (var p in Process.GetProcesses().OrderByDescending(x =>
        {
            try { return x.WorkingSet64; }
            catch { return 0L; }
        }).Take(limit))
        {
            try
            {
                list.Add(new
                {
                    pid = p.Id,
                    ppid = 0,
                    user = \"\",
                    cpu = 0.0,
                    mem = 0.0,
                    rss = p.WorkingSet64,
                    vsz = p.VirtualMemorySize64,
                    stat = \"R\",
                    etime = \"\",
                    args = p.ProcessName,
                });
            }
            catch { /* skip */ }
            finally { p.Dispose(); }
        }
        return list;
    }
""")
        text = text.replace(
            "    private static async Task SendJson(WebSocket ws, object obj, CancellationToken ct)\n",
            helpers + "\n    private static async Task SendJson(WebSocket ws, object obj, CancellationToken ct)\n",
        )
    write_utf8(path, text)
    print("patched PanelWebSocketHub.cs")

def patch_program_cs() -> None:
    path = ROOT / "deploy" / "cloud-monitor-dotnet" / "CloudMonitor.Panel" / "CloudMonitor.Panel" / "Program.cs"
    text = path.read_text(encoding="utf-8")
    if "PanelDashboardHistory" not in text:
        text = text.replace(
            "builder.Services.AddSingleton<MetricsStoreService>();\n",
            "builder.Services.AddSingleton<MetricsStoreService>();\nbuilder.Services.AddSingleton<PanelDashboardHistory>();\n",
        )
        text = text.replace(
            "var metricsStore = app.Services.GetRequiredService<MetricsStoreService>();\n",
            "var metricsStore = app.Services.GetRequiredService<MetricsStoreService>();\nvar dashHistory = app.Services.GetRequiredService<PanelDashboardHistory>();\n",
        )
        text = text.replace(
            "PanelApiExtensions.MapExtendedApi(api, opts, col, panelCfg, metricsStore, pkgSvc);\n",
            "PanelApiExtensions.MapExtendedApi(api, opts, col, panelCfg, metricsStore, pkgSvc, dashHistory);\n",
        )
    write_utf8(path, text)
    print("patched Program.cs")

def patch_panel_api_extensions() -> None:
    path = SERVICES / "PanelApiExtensions.cs"
    text = path.read_text(encoding="utf-8")
    old_sig = "    public static void MapExtendedApi(RouteGroupBuilder api, PanelOptions opts, WindowsCollectorService col, PanelConfig cfg, MetricsStoreService metricsStore, WindowsPackageService pkgSvc)\n"
    new_sig = "    public static void MapExtendedApi(RouteGroupBuilder api, PanelOptions opts, WindowsCollectorService col, PanelConfig cfg, MetricsStoreService metricsStore, WindowsPackageService pkgSvc, PanelDashboardHistory dashHistory)\n"
    if "PanelDashboardHistory dashHistory" not in text:
        if old_sig not in text:
            raise SystemExit("MapExtendedApi signature not found")
        text = text.replace(old_sig, new_sig)
    text = text.replace(
        '        api.MapGet("/history", (string? range) => Results.Json(new { range = range ?? "10m", points = Array.Empty<object>() }));\n',
        '        api.MapGet("/history", (string? range) => Results.Json(dashHistory.GetHistory(range)));\n',
    )
    write_utf8(path, text)
    print("patched PanelApiExtensions.cs")

def main() -> None:
    patch_windows_collector()
    create_panel_dashboard_history()
    patch_panel_websocket_hub()
    patch_program_cs()
    patch_panel_api_extensions()

if __name__ == "__main__":
    main()