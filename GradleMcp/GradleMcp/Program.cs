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
app.Run();
