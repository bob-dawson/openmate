using System.ComponentModel;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Hosting;
using ModelContextProtocol.Server;

namespace GradleMcp
{
    [McpServerToolType]
    public static class GradleTools
    {
        private static readonly TimeSpan OutputIdleTimeout = TimeSpan.FromSeconds(20);
        private static readonly Regex AnsiEscapeRegex = new(@"\x1B\[[0-?]*[ -/]*[@-~]", RegexOptions.Compiled);
        private static readonly object Sync = new();
        private static readonly object LogSync = new();
        private static RunningInvocation? _current;
        private static string? _currentLogPath;

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
            var runtimeDirectory = GetLogDirectory();
            Directory.CreateDirectory(runtimeDirectory);

            var timestamp = DateTimeOffset.Now.ToString("yyyyMMdd-HHmmss-fff");
            var invocationId = Guid.NewGuid().ToString("n");
            var filePrefix = Path.Combine(runtimeDirectory, $"{timestamp}_{invocationId}");
            var stdoutPath = filePrefix + ".stdout.log";
            var stderrPath = filePrefix + ".stderr.log";
            var invocationPath = filePrefix + ".invocation.json";
            var debugLogPath = filePrefix + ".debug.log";

            SetCurrentLogPath(debugLogPath);
            Log("run_gradle: request received", $"cwd={requestedDirectory}", $"args={string.Join(" ", args)}", $"timeoutMs={timeoutMs}");
            var command = BuildCmdCommand(wrapper, args);
            Log($"{invocationId}: resolved wrapper", $"wrapper={wrapper}", $"workdir={androidDirectory}");
            WriteLatestInvocation(
                invocationPath,
                invocationId,
                requestedDirectory,
                androidDirectory,
                wrapper,
                args,
                command,
                stdoutPath,
                stderrPath,
                timeoutMs);

            var startInfo = new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = $"/d /s /c \"{command}\"",
                WorkingDirectory = androidDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
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
            var idleTimedOut = false;
            var stdoutCollector = new LineCollector(stdoutPath);
            var stderrCollector = new LineCollector(stderrPath);
            Task? stdoutPump = null;
            Task? stderrPump = null;
            using var outputActivity = new OutputActivityTracker();
            try
            {
                process.Start();
                stdoutPump = PumpReaderAsync(process.StandardOutput, stdoutCollector, "stdout", invocationId, outputActivity);
                stderrPump = PumpReaderAsync(process.StandardError, stderrCollector, "stderr", invocationId, outputActivity);
                Log($"{invocationId}: process started", $"pid={process.Id}");

                using var cts = new CancellationTokenSource(timeoutMs);
                try
                {
                    Log($"{invocationId}: waiting for exit");
                    await WaitForExitOrIdleAsync(process, outputActivity, cts.Token, invocationId);
                    Log($"{invocationId}: process exited", $"exitCode={process.ExitCode}");
                }
                catch (OutputIdleTimeoutException ex)
                {
                    idleTimedOut = true;
                    Log($"{invocationId}: output idle timeout", ex.Message);
                    TryKillProcessTree(process);
                    await process.WaitForExitAsync();
                    Log($"{invocationId}: process killed after idle timeout", $"exitCode={process.ExitCode}");
                }
                catch
                {

                }

                if (!process.HasExited && !idleTimedOut)
                {
                    timedOut = true;
                    Log($"{invocationId}: process wait timed out", $"timeoutMs={timeoutMs}");
                    TryKillProcessTree(process);
                    await process.WaitForExitAsync();
                    Log($"{invocationId}: process killed after timeout", $"exitCode={process.ExitCode}");
                }


                await Task.WhenAll(stdoutPump ?? Task.CompletedTask, stderrPump ?? Task.CompletedTask);
                Log($"{invocationId}: reading output tails");
                var stdoutTail = stdoutCollector.GetTail();
                var stderrTail = stderrCollector.GetTail();
                var durationMs = (long)(DateTimeOffset.UtcNow - GetStartTime(invocationId)).TotalMilliseconds;
                var combinedTail = TakeTail(CombineOutput(stdoutTail, stderrTail), 12000);
                Log(
                    $"{invocationId}: preparing response",
                    $"durationMs={durationMs}",
                    $"stdoutTailLength={stdoutTail.Length}",
                    $"stderrTailLength={stderrTail.Length}",
                    $"combinedTailLength={combinedTail.Length}"
                );

                return string.Join("\n", [
                    $"ok: {!timedOut && !idleTimedOut && process.ExitCode == 0}",
                    $"exitCode: {process.ExitCode}",
                    $"timedOut: {timedOut}",
                    $"idleTimedOut: {idleTimedOut}",
                    $"durationMs: {durationMs}",
                    "stdoutTail:",
                    stdoutTail,
                    "stderrTail:",
                    stderrTail,
                    "combinedTail:",
                    combinedTail,
                ]);
            }
            catch (Exception ex)
            {
                Log($"{invocationId}: run_gradle failed", ex.ToString());
                return $"error: {ex.Message}";
            }
            finally
            {
                if (stdoutPump is not null && !stdoutPump.IsCompleted)
                {
                    await AwaitQuietly(stdoutPump);
                }

                if (stderrPump is not null && !stderrPump.IsCompleted)
                {
                    await AwaitQuietly(stderrPump);
                }

                Log($"{invocationId}: clearing invocation");
                ClearInvocation(invocationId);
                SetCurrentLogPath(null);
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
                Log("stop_gradle: no running task");
                return "stopped: false\nhadRunningTask: false\nmessage: no Gradle command is running";
            }

            Log($"{invocation.Id}: stop requested");
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
            Log($"{invocation.Id}: stop finished", $"stopped={stopped}");
            return string.Join("\n", [
                $"stopped: {stopped}",
                "hadRunningTask: true",
                stopped
                    ? "message: requested termination of the current Gradle process tree"
                    : "message: failed to terminate the current Gradle process tree",
            ]);
        }

        public static Task StopActiveProcessAsync()
        {
            RunningInvocation? invocation;
            lock (Sync)
            {
                invocation = _current;
            }

            if (invocation is null)
            {
                return Task.CompletedTask;
            }

            Log($"{invocation.Id}: service shutdown requested");
            TryKillProcessTree(invocation.Process);
            ClearInvocation(invocation.Id);
            return Task.CompletedTask;
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

        private static string BuildCmdCommand(string wrapperPath, IEnumerable<string> args)
        {
            var builder = new StringBuilder();
            builder.Append(QuoteForCmd(wrapperPath));
            foreach (var arg in args)
            {
                builder.Append(' ');
                builder.Append(QuoteForCmd(arg));
            }

            return builder.ToString();
        }

        private static string QuoteForCmd(string value)
        {
            return '"' + value.Replace("\"", "\"\"") + '"';
        }

        private static string GetLogDirectory()
        {
            return Path.Combine(AppContext.BaseDirectory, "logs");
        }

        private static void WriteLatestInvocation(
            string path,
            string invocationId,
            string requestedDirectory,
            string androidDirectory,
            string wrapper,
            IEnumerable<string> args,
            string command,
            string stdoutPath,
            string stderrPath,
            int timeoutMs)
        {
            try
            {
                var payload = new
                {
                    invocationId,
                    requestedDirectory,
                    androidDirectory,
                    wrapper,
                    args = args.ToArray(),
                    command,
                    stdoutPath,
                    stderrPath,
                    timeoutMs,
                    writtenAt = DateTimeOffset.UtcNow,
                };
                var json = JsonSerializer.Serialize(payload, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(path, json, Encoding.UTF8);
                Log($"{invocationId}: wrote invocation metadata", $"metadataPath={path}");
            }
            catch (Exception ex)
            {
                Log($"{invocationId}: failed to write invocation metadata", ex.Message);
            }
        }

        private static void SetCurrentLogPath(string? path)
        {
            lock (LogSync)
            {
                _currentLogPath = path;
            }
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
                    Log("kill_process_tree", $"pid={process.Id}");
                    process.Kill(entireProcessTree: true);
                }

                return true;
            }
            catch
            {
                return false;
            }
        }

        private static async Task PumpReaderAsync(
            StreamReader reader,
            LineCollector collector,
            string streamName,
            string invocationId,
            OutputActivityTracker activityTracker)
        {
            var buffer = new char[1024];
            while (true)
            {
                var read = await reader.ReadAsync(buffer, 0, buffer.Length);
                if (read == 0)
                {
                    Log($"{invocationId}: {streamName} stream completed");
                    return;
                }

                activityTracker.MarkActivity();
                var chunk = NormalizeOutputChunk(new string(buffer, 0, read));
                if (chunk.Length == 0)
                {
                    continue;
                }

                collector.AppendText(chunk);
                Console.Write(chunk);
            }
        }

        private static async Task WaitForExitOrIdleAsync(
            Process process,
            OutputActivityTracker activityTracker,
            CancellationToken cancellationToken,
            string invocationId)
        {
            while (true)
            {
                cancellationToken.ThrowIfCancellationRequested();

                if (process.HasExited)
                {
                    await process.WaitForExitAsync(cancellationToken);
                    return;
                }

                var idleFor = DateTimeOffset.UtcNow - activityTracker.LastActivityUtc;
                if (idleFor >= OutputIdleTimeout)
                {
                    throw new OutputIdleTimeoutException($"no stdout/stderr update for {OutputIdleTimeout.TotalSeconds:0} seconds");
                }

                var remaining = OutputIdleTimeout - idleFor;
                var delay = remaining < TimeSpan.FromMilliseconds(250) ? remaining : TimeSpan.FromMilliseconds(250);
                //Log($"{invocationId}: waiting heartbeat", $"idleForMs={(long)idleFor.TotalMilliseconds}");
                await Task.Delay(delay, cancellationToken);
            }
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

        private static string TakeTail(string value, int maxChars)
        {
            if (value.Length <= maxChars)
            {
                return value;
            }

            return "... tail truncated ...\n" + value[^maxChars..];
        }

        private static string NormalizeOutputChunk(string chunk)
        {
            if (string.IsNullOrEmpty(chunk))
            {
                return string.Empty;
            }

            var normalized = chunk.Replace("\r\n", "\n").Replace('\r', '\n');
            normalized = AnsiEscapeRegex.Replace(normalized, string.Empty);

            var builder = new StringBuilder(normalized.Length);
            foreach (var ch in normalized)
            {
                if (ch == '\n' || ch == '\t' || !char.IsControl(ch))
                {
                    builder.Append(ch);
                }
            }

            return builder.ToString();
        }

        private static void Log(params string[] parts)
        {
            try
            {
                lock (LogSync)
                {
                    var logPath = _currentLogPath ?? Path.Combine(GetLogDirectory(), "bootstrap.log");
                    Directory.CreateDirectory(Path.GetDirectoryName(logPath)!);
                    var line = $"[{DateTimeOffset.UtcNow:O}] {string.Join(" | ", parts)}{Environment.NewLine}";
                    File.AppendAllText(logPath, line, Encoding.UTF8);
                    Console.WriteLine(line);
                }
            }
            catch
            {
            }
        }

        private static async Task AwaitQuietly(Task task)
        {
            try
            {
                await task;
            }
            catch
            {
            }
        }

        private sealed class OutputActivityTracker : IDisposable
        {
            private DateTimeOffset _lastActivityTicks = DateTimeOffset.UtcNow;

            public DateTimeOffset LastActivityUtc => _lastActivityTicks;

            public void MarkActivity()
            {
                _lastActivityTicks = DateTimeOffset.UtcNow;
            }

            public void Dispose()
            {
            }
        }

        private sealed class OutputIdleTimeoutException(string message) : Exception(message);

        private sealed record RunningInvocation(
            string Id,
            Process Process,
            string StdoutPath,
            string StderrPath,
            string WorkingDirectory,
            DateTimeOffset StartedAt);

        private sealed class LineCollector
        {
            private readonly string _path;
            private readonly StringBuilder _tail = new();
            private readonly object _sync = new();

            public LineCollector(string path)
            {
                _path = path;
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.WriteAllText(path, string.Empty, Encoding.UTF8);
            }

            public void AppendText(string text)
            {
                lock (_sync)
                {
                    File.AppendAllText(_path, text, Encoding.UTF8);
                    _tail.Append(text);
                    if (_tail.Length > 16000)
                    {
                        _tail.Remove(0, _tail.Length - 16000);
                    }
                }
            }

            public string GetTail()
            {
                lock (_sync)
                {
                    return TakeTail(_tail.ToString(), 12000);
                }
            }
        }
    }

    public sealed class GradleShutdownService : IHostedService
    {
        public Task StartAsync(CancellationToken cancellationToken) => Task.CompletedTask;

        public Task StopAsync(CancellationToken cancellationToken) => GradleTools.StopActiveProcessAsync();
    }
}
