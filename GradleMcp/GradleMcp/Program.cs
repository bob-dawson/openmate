using GradleMcp;
using Microsoft.AspNetCore.Server.Kestrel.Core;

var builder = WebApplication.CreateBuilder(args);

builder.Host.UseWindowsService();
builder.WebHost.UseUrls("http://localhost:5099");
builder.WebHost.ConfigureKestrel(options =>
{
    options.AddServerHeader = false;
    options.ConfigureEndpointDefaults(endpoint =>
    {
        endpoint.Protocols = HttpProtocols.Http1;
    });
});

builder.Services.AddSingleton<IHostedService, GradleShutdownService>();
builder.Services.AddMcpServer().WithHttpTransport().WithToolsFromAssembly();

var app = builder.Build();

app.MapMcp("/mcp");

app.MapPost("/api/gradle/run", async (HttpContext ctx) =>
{
    var body = await ctx.Request.ReadFromJsonAsync<GradleRunRequest>(ctx.RequestAborted);
    if (body is null || body.Args is null || body.Args.Length == 0)
    {
        ctx.Response.StatusCode = 400;
        await ctx.Response.WriteAsJsonAsync(new { error = "args must not be empty" });
        return;
    }

    var result = await GradleTools.RunGradle(body.Args, body.Cwd, body.TimeoutMs ?? 600000);
    await ctx.Response.WriteAsJsonAsync(new { result });
});

app.MapPost("/api/gradle/stop", async (HttpContext ctx) =>
{
    var result = await GradleTools.StopGradle();
    await ctx.Response.WriteAsJsonAsync(new { result });
});

app.MapGet("/api/gradle/status", (HttpContext ctx) =>
{
    var status = GradleTools.GetStatus();
    if (status is null)
    {
        return Results.Json(new { running = false });
    }
    return Results.Json(new { running = true, invocation = status });
});

app.Run();

record GradleRunRequest(string[] Args, string? Cwd, int? TimeoutMs);
