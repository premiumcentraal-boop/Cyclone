#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$proj = Join-Path $here 'csharp'
New-Item -ItemType Directory -Force -Path $proj, (Join-Path $here 'dist') | Out-Null

@'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>WinExe</OutputType>
    <TargetFramework>net8.0-windows</TargetFramework>
    <UseWindowsForms>true</UseWindowsForms>
    <Nullable>enable</Nullable>
    <ImplicitUsings>enable</ImplicitUsings>
    <AssemblyName>CyclonePcGlassHelper</AssemblyName>
    <RootNamespace>CyclonePcGlass</RootNamespace>
    <PublishSingleFile>true</PublishSingleFile>
    <SelfContained>true</SelfContained>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
    <IncludeNativeLibrariesForSelfExtract>true</IncludeNativeLibrariesForSelfExtract>
  </PropertyGroup>
</Project>
'@ | Set-Content (Join-Path $proj 'CyclonePcGlassHelper.csproj')

@'
using System.Diagnostics;
using System.Net;
using System.Text;

namespace CyclonePcGlass;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}

sealed class MainForm : Form
{
    readonly Label _title = new() { AutoSize = false, Location = new Point(16, 16), Size = new Size(380, 40) };
    readonly Label _detail = new() { AutoSize = false, Location = new Point(16, 60), Size = new Size(380, 40) };
    readonly Button _start = new() { Text = "Start Glass", Location = new Point(16, 120), Size = new Size(110, 36) };
    readonly Button _stop = new() { Text = "Stop Glass", Location = new Point(140, 120), Size = new Size(110, 36) };
    readonly Button _open = new() { Text = "Open UI", Location = new Point(264, 120), Size = new Size(110, 36) };
    readonly LinkLabel _logs = new() { Text = "Logs folder", Location = new Point(16, 170), AutoSize = true };
    readonly System.Windows.Forms.Timer _timer = new() { Interval = 4000 };

    public MainForm()
    {
        Text = "Cyclone PC Glass";
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        ClientSize = new Size(420, 210);
        Font = new Font("Segoe UI", 10F);
        Controls.AddRange(new Control[] { _title, _detail, _start, _stop, _open, _logs });
        _start.Click += (_, _) => RunStart();
        _stop.Click += (_, _) => RunStop();
        _open.Click += (_, _) => Process.Start(new ProcessStartInfo("http://127.0.0.1:8000") { UseShellExecute = true });
        _logs.LinkClicked += (_, _) => Process.Start("explorer.exe", Glass.LogDir);
        _timer.Tick += (_, _) => RefreshStatus();
        _timer.Start();
        RefreshStatus();
    }

    void RefreshStatus()
    {
        if (Glass.IsUp())
        {
            _title.Text = "Cyclone PC Glass is running";
            _detail.Text = "http://127.0.0.1:8000";
            _detail.ForeColor = Color.ForestGreen;
            _start.Enabled = false;
            _stop.Enabled = true;
            _open.Enabled = true;
        }
        else
        {
            _title.Text = "Cyclone PC Glass is stopped";
            _detail.Text = "Mode A: CYCLONE_CONNECTED=1 · session default-foreground";
            _detail.ForeColor = Color.DimGray;
            _start.Enabled = true;
            _stop.Enabled = false;
            _open.Enabled = false;
        }
    }

    void RunStart()
    {
        _start.Enabled = false;
        _title.Text = "Starting (no console)…";
        Refresh();
        var msg = Glass.Start();
        MessageBox.Show(msg, "Cyclone PC Glass");
        RefreshStatus();
    }

    void RunStop()
    {
        _stop.Enabled = false;
        _title.Text = "Stopping…";
        Refresh();
        var msg = Glass.Stop();
        MessageBox.Show(msg, "Cyclone PC Glass");
        RefreshStatus();
    }
}

static class Glass
{
    public static string Root
    {
        get
        {
            // exe next to windows-helper, or inside windows-helper/dist
            var dir = AppContext.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var helper = Directory.Exists(Path.Combine(dir, "GlassControl.ps1")) ? dir
                : Directory.Exists(Path.Combine(dir, "..", "GlassControl.ps1")) ? Path.GetFullPath(Path.Combine(dir, ".."))
                : dir;
            return Path.GetFullPath(Path.Combine(helper, ".."));
        }
    }

    public static string LogDir
    {
        get
        {
            var d = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CyclonePcGlass", "logs");
            Directory.CreateDirectory(d);
            return d;
        }
    }

    static void Log(string m) =>
        File.AppendAllText(Path.Combine(LogDir, "helper.log"), $"{DateTime.Now:u} {m}{Environment.NewLine}", Encoding.UTF8);

    public static bool IsUp(int port = 8000)
    {
        try
        {
            var req = (HttpWebRequest)WebRequest.Create($"http://127.0.0.1:{port}/api/status");
            req.Timeout = 2000;
            using var _ = req.GetResponse();
            return true;
        }
        catch { return false; }
    }

    static string? FindUv()
    {
        var path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var part in path.Split(Path.PathSeparator))
        {
            var cand = Path.Combine(part, "uv.exe");
            if (File.Exists(cand)) return cand;
        }
        return null;
    }

    public static string Start(int port = 8000)
    {
        if (IsUp(port)) return $"Already running on http://127.0.0.1:{port}";
        var uv = FindUv() ?? throw new InvalidOperationException("uv not found on PATH");
        var psi = new ProcessStartInfo
        {
            FileName = uv,
            Arguments = $"run python -m artemis ui --port {port} --no-open",
            WorkingDirectory = Root,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.Environment["CYCLONE_CONNECTED"] = "1";
        psi.Environment["CYCLONE_DEVICE_GATEWAY_URL"] =
            Environment.GetEnvironmentVariable("CYCLONE_DEVICE_GATEWAY_URL") ?? "http://127.0.0.1:8765";
        psi.Environment["CYCLONE_SESSION_ID"] =
            Environment.GetEnvironmentVariable("CYCLONE_SESSION_ID") ?? "default-foreground";
        Log($"Starting in {Root}");
        var p = Process.Start(psi) ?? throw new InvalidOperationException("failed to start");
        for (var i = 0; i < 45; i++)
        {
            Thread.Sleep(2000);
            if (IsUp(port)) { Log($"up pid={p.Id}"); return $"Started — http://127.0.0.1:{port}"; }
            if (p.HasExited) return "Process exited early (see %LOCALAPPDATA%\\CyclonePcGlass\\logs)";
        }
        return "Timed out waiting for :8000";
    }

    public static string Stop(int port = 8000)
    {
        var uv = FindUv() ?? throw new InvalidOperationException("uv not found on PATH");
        var psi = new ProcessStartInfo
        {
            FileName = uv,
            Arguments = $"run python -m artemis stop --port {port}",
            WorkingDirectory = Root,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        Log("Stopping");
        var p = Process.Start(psi)!;
        p.WaitForExit(60000);
        Thread.Sleep(1000);
        return IsUp(port) ? "Stop finished but port still responds" : "Stopped";
    }
}
'@ | Set-Content (Join-Path $proj 'Program.cs')

Push-Location $proj
try {
  dotnet publish -c Release -o (Join-Path $here 'dist')
} finally {
  Pop-Location
}
Write-Host "Published to $here\dist\CyclonePcGlassHelper.exe"
