from pathlib import Path
path = Path(r"D:\Project\ms\ServerMaintance\deploy\cloud-monitor-dotnet\CloudMonitor.Panel\CloudMonitor.Panel\Services\PanelWebSocketHub.cs")
text = path.read_text(encoding="utf-8")
marker = "    private static List<object> TopProcessesLite"
if marker in text:
    raise SystemExit("helpers already present")
helpers = """

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
"""
needle = "    private static async Task SendJson(WebSocket ws, object obj, CancellationToken ct)\n"
if needle not in text:
    raise SystemExit("SendJson not found")
text = text.replace(needle, helpers + "\n" + needle)
path.write_text(text, encoding="utf-8", newline="\n")
print("ok")