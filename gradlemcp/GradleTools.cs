using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using ModelContextProtocol.Server;

namespace gradlemcp;

[McpServerToolType]
public static class GradleTools
{
    private static readonly object Sync = new();
    private static RunningInvocation? _current;

    [McpServerTool]
    [Description("Run the android Gradle wrapper found from cwd by searching ancestor directories for android/gradlew.bat. Rejects concurrent runs.")]
    public static async Task<string> RunGradle(
        [Description("Gradle arguments, for example [':app:assembleDebug']")] string[] args,
        [Description("Starting directory used to locate the workspace that contains android/gradlew.bat")] string? cwd = null,
        [Description("Timeout in milliseconds")] int timeoutMs = 600000)
    {
        if (args.Length == 0)
        {
            return "error: args must not be empty";
        }

        if (timeoutMs <= 0)
        {
            return "error: timeoutMs must be greater than zero";
        }

        var requestedDirectory = ResolveRequestedDirectory(cwd);
        var wrapper = FindGradleWrapper(requestedDirectory);
        if (wrapper is null)
        {
            return $"error: could not find android{Path.DirectorySeparatorChar}gradlew.bat from '{requestedDirectory}' or its ancestors";
        }

        var androidDirectory = Path.GetDirectoryName(wrapper)!;
        var runtimeDirectory = Path.Combine(Path.GetTempPath(), "gradlemcp");
        Directory.CreateDirectory(runtimeDirectory);

        var invocationId = Guid.NewGuid().ToString("n");
        var stdoutPath = Path.Combine(runtimeDirectory, $"{invocationId}.stdout.log");
        var stderrPath = Path.Combine(runtimeDirectory, $"{invocationId}.stderr.log");
        var command = BuildCmdCommand(wrapper, args, stdoutPath, stderrPath);

        var startInfo = new ProcessStartInfo
        {
            FileName = "cmd.exe",
            Arguments = $"/d /s /c \"{command}\"",
            WorkingDirectory = androidDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        var process = new Process
        {
            StartInfo = startInfo,
            EnableRaisingEvents = true,
        };

        if (!TryRegisterInvocation(new RunningInvocation(invocationId, process, stdoutPath, stderrPath, androidDirectory, DateTimeOffset.UtcNow)))
        {
            return "error: another Gradle command is already running; use stop_gradle before starting a new one";
        }

        var timedOut = false;
        try
        {
            process.Start();

            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await process.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                timedOut = true;
                TryKillProcessTree(process);
                await process.WaitForExitAsync();
            }

            await Task.Delay(200);

            var stdout = ReadFileIfExists(stdoutPath);
            var stderr = ReadFileIfExists(stderrPath);
            var durationMs = (long)(DateTimeOffset.UtcNow - GetStartTime(invocationId)).TotalMilliseconds;
            var combinedTail = TakeTail(CombineOutput(stdout, stderr), 12000);

            return string.Join("\n", [
                $"ok: {!timedOut && process.ExitCode == 0}",
                $"exitCode: {process.ExitCode}",
                $"timedOut: {timedOut}",
                $"durationMs: {durationMs}",
                "stdout:",
                LimitText(stdout, 200000),
                "stderr:",
                LimitText(stderr, 200000),
                "combinedTail:",
                combinedTail,
            ]);
        }
        catch (Exception ex)
        {
            return $"error: {ex.Message}";
        }
        finally
        {
            ClearInvocation(invocationId);
        }
    }

    [McpServerTool]
    [Description("Stop the currently running Gradle command by terminating its process tree.")]
    public static async Task<string> StopGradle()
    {
        RunningInvocation? invocation;
        lock (Sync)
        {
            invocation = _current;
        }

        if (invocation is null)
        {
            return "stopped: false\nhadRunningTask: false\nmessage: no Gradle command is running";
        }

        var stopped = TryKillProcessTree(invocation.Process);
        try
        {
            if (!invocation.Process.HasExited)
            {
                using var cts = new CancellationTokenSource(5000);
                await invocation.Process.WaitForExitAsync(cts.Token);
            }
        }
        catch
        {
        }

        ClearInvocation(invocation.Id);
        return string.Join("\n", [
            $"stopped: {stopped}",
            "hadRunningTask: true",
            stopped
                ? "message: requested termination of the current Gradle process tree"
                : "message: failed to terminate the current Gradle process tree",
        ]);
    }

    private static string ResolveRequestedDirectory(string? cwd)
    {
        var candidate = string.IsNullOrWhiteSpace(cwd) ? Environment.CurrentDirectory : cwd.Trim();
        return Path.GetFullPath(candidate);
    }

    private static string? FindGradleWrapper(string startDirectory)
    {
        var current = new DirectoryInfo(startDirectory);
        while (current is not null)
        {
            var candidate = Path.Combine(current.FullName, "android", "gradlew.bat");
            if (File.Exists(candidate))
            {
                return candidate;
            }

            current = current.Parent;
        }

        return null;
    }

    private static string BuildCmdCommand(string wrapperPath, IEnumerable<string> args, string stdoutPath, string stderrPath)
    {
        var builder = new StringBuilder();
        builder.Append(QuoteForCmd(wrapperPath));
        foreach (var arg in args)
        {
            builder.Append(' ');
            builder.Append(QuoteForCmd(arg));
        }

        builder.Append(" 1>");
        builder.Append(QuoteForCmd(stdoutPath));
        builder.Append(" 2>");
        builder.Append(QuoteForCmd(stderrPath));
        return builder.ToString();
    }

    private static string QuoteForCmd(string value)
    {
        return '"' + value.Replace("\"", "\"\"") + '"';
    }

    private static bool TryRegisterInvocation(RunningInvocation invocation)
    {
        lock (Sync)
        {
            if (_current is not null)
            {
                return false;
            }

            _current = invocation;
            return true;
        }
    }

    private static void ClearInvocation(string invocationId)
    {
        lock (Sync)
        {
            if (_current?.Id == invocationId)
            {
                _current = null;
            }
        }
    }

    private static DateTimeOffset GetStartTime(string invocationId)
    {
        lock (Sync)
        {
            if (_current?.Id == invocationId)
            {
                return _current.StartedAt;
            }
        }

        return DateTimeOffset.UtcNow;
    }

    private static bool TryKillProcessTree(Process process)
    {
        try
        {
            if (!process.HasExited)
            {
                process.Kill(entireProcessTree: true);
            }

            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string ReadFileIfExists(string path)
    {
        if (!File.Exists(path))
        {
            return string.Empty;
        }

        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: true);
        return reader.ReadToEnd();
    }

    private static string CombineOutput(string stdout, string stderr)
    {
        if (string.IsNullOrEmpty(stdout))
        {
            return stderr;
        }

        if (string.IsNullOrEmpty(stderr))
        {
            return stdout;
        }

        return stdout + "\n" + stderr;
    }

    private static string LimitText(string value, int maxChars)
    {
        if (value.Length <= maxChars)
        {
            return value;
        }

        return value[..maxChars] + $"\n... truncated to first {maxChars} characters ...";
    }

    private static string TakeTail(string value, int maxChars)
    {
        if (value.Length <= maxChars)
        {
            return value;
        }

        return "... tail truncated ...\n" + value[^maxChars..];
    }

    private sealed record RunningInvocation(
        string Id,
        Process Process,
        string StdoutPath,
        string StderrPath,
        string WorkingDirectory,
        DateTimeOffset StartedAt);
}
