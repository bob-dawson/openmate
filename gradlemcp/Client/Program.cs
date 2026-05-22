using System.Diagnostics;
using ModelContextProtocol.Client;
using ModelContextProtocol.Protocol;

var start = DateTimeOffset.Now;
Console.WriteLine($"[{start:O}] Starting MCP client debug run");
var transport = new HttpClientTransport(new HttpClientTransportOptions { Endpoint = new Uri("http://localhost:5099/mcp") });

try
{
    Console.WriteLine($"[{DateTimeOffset.Now:O}] Connecting with official MCP C# SDK");
    await using var client = await McpClient.CreateAsync(transport);
    Console.WriteLine($"[{DateTimeOffset.Now:O}] Connected");

    var tools = await client.ListToolsAsync();
    Console.WriteLine($"[{DateTimeOffset.Now:O}] Tools ({tools.Count}):");
    foreach (var tool in tools)
    {
        Console.WriteLine($"- {tool.Name}: {tool.Description}");
    }

    Console.WriteLine($"[{DateTimeOffset.Now:O}] Calling run_gradle with fixed debug arguments");
    var sw = Stopwatch.StartNew();
    var result = await client.CallToolAsync(
        "run_gradle",
        new Dictionary<string, object?>
        {
            ["args"] = new[] { ":core:network:testDebugUnitTest" },
            ["cwd"] = "D:\\openmate",
            ["timeoutMs"] = 300000,
        },
        cancellationToken: CancellationToken.None);
    sw.Stop();

    Console.WriteLine($"[{DateTimeOffset.Now:O}] Call completed in {sw.ElapsedMilliseconds} ms");
    Console.WriteLine("=== Tool Result ===");
    foreach (var content in result.Content)
    {
        switch (content)
        {
            case TextContentBlock text:
                Console.WriteLine(text.Text);
                break;
            default:
                Console.WriteLine(content.ToString());
                break;
        }
    }
}
catch (Exception ex)
{
    Console.WriteLine($"[{DateTimeOffset.Now:O}] MCP client failed");
    Console.WriteLine(ex.ToString());
    Environment.ExitCode = 1;
}
