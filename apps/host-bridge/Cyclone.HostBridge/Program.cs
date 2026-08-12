using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

var builder = WebApplication.CreateBuilder(args);
var settings = HostBridgeSettings.FromConfiguration(builder.Configuration);
builder.Services.AddSingleton(settings);
builder.Services.AddSingleton<AuditLog>();
builder.Services.AddSingleton<HostPolicy>();

var app = builder.Build();
app.Urls.Clear();
app.Urls.Add($"http://127.0.0.1:{settings.Port}");

app.Use(async (context, next) =>
{
    if (context.Request.Path == "/health")
    {
        await next();
        return;
    }

    var presented = context.Request.Headers.Authorization.ToString();
    const string prefix = "Bearer ";
    if (!presented.StartsWith(prefix, StringComparison.Ordinal) ||
        !CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(presented[prefix.Length..]),
            Encoding.UTF8.GetBytes(settings.Token)))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await context.Response.WriteAsJsonAsync(new { detail = "Invalid Host Bridge credential." });
        return;
    }

    await next();
});

app.MapGet("/health", () => Results.Ok(new
{
    status = "ok",
    service = "cyclone-host-bridge",
    bind = "127.0.0.1",
    workspaceRoot = settings.WorkspaceRoot,
}));

app.MapPost("/v1/execute", async (HostToolRequest request, HostPolicy policy, AuditLog audit, CancellationToken cancellationToken) =>
{
    var decision = policy.Evaluate(request);
    if (!decision.Allowed)
    {
        await audit.WriteAsync(request, "denied", decision.Reason, cancellationToken);
        return Results.Json(new HostToolResponse(false, null, decision.Reason), statusCode: StatusCodes.Status403Forbidden);
    }

    try
    {
        object result = request.Capability switch
        {
            "filesystem.read" => await ReadFileAsync(request.Target, cancellationToken),
            "process.list" => Process.GetProcesses().OrderBy(p => p.ProcessName).Select(p => new { p.Id, p.ProcessName }).ToArray(),
            "window.list" => Process.GetProcesses().Where(p => !string.IsNullOrEmpty(p.MainWindowTitle)).Select(p => new { p.Id, p.ProcessName, p.MainWindowTitle }).ToArray(),
            _ => throw new InvalidOperationException("Capability must be approved by Core and implemented explicitly before it can execute."),
        };
        await audit.WriteAsync(request, "completed", "Allowlisted read-only capability executed.", cancellationToken);
        return Results.Ok(new HostToolResponse(true, result, "Completed."));
    }
    catch (Exception exception)
    {
        await audit.WriteAsync(request, "failed", exception.GetType().Name, cancellationToken);
        return Results.Json(new HostToolResponse(false, null, "Host capability failed; see local audit log."), statusCode: StatusCodes.Status500InternalServerError);
    }
});

app.Run();

static async Task<object> ReadFileAsync(string path, CancellationToken cancellationToken)
{
    var info = new FileInfo(path);
    if (!info.Exists)
    {
        throw new FileNotFoundException("Requested file was not found.");
    }
    if (info.Length > 1_000_000)
    {
        throw new InvalidOperationException("Read exceeds the 1 MB Host Bridge safety limit.");
    }
    var content = await File.ReadAllTextAsync(path, cancellationToken);
    return new { path, content, bytes = info.Length };
}

public sealed record HostToolRequest(string Capability, string Target, Dictionary<string, JsonElement>? Arguments, string CorrelationId, bool ApprovalGranted);
public sealed record HostToolResponse(bool Ok, object? Result, string Detail);

public sealed class HostBridgeSettings
{
    public required string Token { get; init; }
    public required string WorkspaceRoot { get; init; }
    public required string AuditDirectory { get; init; }
    public int Port { get; init; } = 8791;

    public static HostBridgeSettings FromConfiguration(IConfiguration config)
    {
        var token = config["CYCLONE_HOST_BRIDGE_TOKEN"];
        if (string.IsNullOrWhiteSpace(token) || token.StartsWith("change-me", StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException("CYCLONE_HOST_BRIDGE_TOKEN must be a non-placeholder local secret.");
        }

        return new HostBridgeSettings
        {
            Token = token,
            WorkspaceRoot = Path.GetFullPath(config["CYCLONE_WORKSPACE_HOST_PATH"] ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "CycloneWorkspace")),
            AuditDirectory = Path.GetFullPath(config["CYCLONE_HOST_BRIDGE_AUDIT_PATH"] ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Cyclone", "HostBridge", "audit")),
            Port = int.TryParse(config["CYCLONE_HOST_BRIDGE_PORT"], out var port) ? port : 8791,
        };
    }
}

public sealed class HostPolicy(HostBridgeSettings settings)
{
    public HostDecision Evaluate(HostToolRequest request)
    {
        var target = Path.GetFullPath(request.Target);
        var workspace = Path.GetFullPath(settings.WorkspaceRoot);
        var inWorkspace = target.StartsWith(workspace.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) || string.Equals(target, workspace, StringComparison.OrdinalIgnoreCase);

        if (request.Capability.StartsWith("filesystem.", StringComparison.Ordinal) && !inWorkspace)
        {
            return new HostDecision(false, "Filesystem target is outside the allowlisted workspace.");
        }

        if (request.Capability is "filesystem.read" or "process.list" or "window.list")
        {
            return new HostDecision(true, "Allowlisted read-only capability.");
        }

        // No generic shell fallback. Core must authorise and the bridge must gain a
        // separately reviewed implementation for any future consequential tool.
        return new HostDecision(false, request.ApprovalGranted
            ? "Capability is not implemented in this Host Bridge version."
            : "Consequential capability requires Core approval and a reviewed bridge implementation.");
    }
}

public sealed record HostDecision(bool Allowed, string Reason);

public sealed class AuditLog(HostBridgeSettings settings)
{
    public async Task WriteAsync(HostToolRequest request, string outcome, string detail, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(settings.AuditDirectory);
        var path = Path.Combine(settings.AuditDirectory, $"{DateTimeOffset.UtcNow:yyyy-MM-dd}.jsonl");
        var record = JsonSerializer.Serialize(new
        {
            occurredAt = DateTimeOffset.UtcNow,
            request.CorrelationId,
            request.Capability,
            request.Target,
            request.ApprovalGranted,
            outcome,
            detail,
        });
        await File.AppendAllTextAsync(path, record + Environment.NewLine, cancellationToken);
    }
}
