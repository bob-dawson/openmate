var builder = WebApplication.CreateBuilder(args);

builder.Services
    .AddMcpServer()
    .WithHttpTransport(options => {
        options.IdleTimeout = TimeSpan.FromHours(1); // Optional configuration
    })
    .WithToolsFromAssembly();

var app = builder.Build();

app.MapMcp("/mcp");

app.Run();

