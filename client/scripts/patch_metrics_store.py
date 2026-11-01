from pathlib import Path
p = Path(r"D:\Project\ms\ServerMaintance\deploy\cloud-monitor-dotnet\CloudMonitor.Panel\CloudMonitor.Panel\Services\MetricsStoreService.cs")
t = p.read_text(encoding="utf-8")
if "private bool EnsureOpen()" not in t:
    t = t.replace("        catch { _disabled = true; }\n    }\n\n    private SqliteConnection Open()", "        catch { }\n    }\n\n    private SqliteConnection Open()", 1)
    ins = """
    private bool EnsureOpen()
    {
        try
        {
            using var c = Open();
            c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = \"CREATE TABLE IF NOT EXISTS samples (ts INTEGER PRIMARY KEY, day TEXT, hr TEXT, cpu REAL, mem REAL, swap REAL, rx INTEGER, tx INTEGER, disk REAL, agg INTEGER NOT NULL DEFAULT 0)\";
            cmd.ExecuteNonQuery();
            cmd.CommandText = \"CREATE INDEX IF NOT EXISTS i_day ON samples(day)\";
            cmd.ExecuteNonQuery();
            cmd.CommandText = \"CREATE INDEX IF NOT EXISTS i_hr ON samples(hr)\";
            cmd.ExecuteNonQuery();
            _disabled = false;
            return true;
        }
        catch { _disabled = true; return false; }
    }
"""
    t = t.replace('    private SqliteConnection Open() => new($"Data Source={_dbPath}");\n\n    public void TryRecord', '    private SqliteConnection Open() => new($"Data Source={_dbPath}");\n' + ins + '\n    public void TryRecord', 1)
    t = t.replace('        if (_disabled) return;\n        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();', '        if (!EnsureOpen()) return;\n        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();', 1)
t = t.replace('                    Rollup(c);', '                    try { Rollup(c); } catch { }', 1)
t = t.replace('        catch { _disabled = true; }\n    }\n\n    private static void Rollup', '        catch { }\n    }\n\n    private static void Rollup', 1)
if 'if (!EnsureOpen()) return new { ok = false, disabled = true };' not in t:
    t = t.replace('        if (_disabled) return new { ok = false, disabled = true };', '        if (!EnsureOpen()) return new { ok = false, disabled = true };', 1)
if 'Query(string? range, bool compare)' in t and 'store disabled' in t:
    t = t.replace('        if (_disabled) return new { ok = false, points = Array.Empty<object>(), agg = false, reason = "store disabled" };', '        if (!EnsureOpen()) return new { ok = false, points = Array.Empty<object>(), agg = false, reason = "store disabled" };', 1)
p.write_text(t, encoding="utf-8")
print("ok")
