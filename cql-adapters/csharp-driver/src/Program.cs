using Microsoft.Extensions.Logging;

namespace LatteDriver;

public class Program
{
    public static async Task<int> Main(string[] args)
    {
        // Configure logging
        using var loggerFactory = LoggerFactory.Create(builder =>
        {
            var logLevel = Environment.GetEnvironmentVariable("LOG_LEVEL") switch
            {
                "trace" or "TRACE" => LogLevel.Trace,
                "debug" or "DEBUG" => LogLevel.Debug,
                "info" or "INFO" => LogLevel.Information,
                "warn" or "WARN" => LogLevel.Warning,
                "error" or "ERROR" => LogLevel.Error,
                _ => LogLevel.Information
            };

            builder.SetMinimumLevel(logLevel);
            builder.AddConsole(options =>
            {
                options.TimestampFormat = "[yyyy-MM-dd HH:mm:ss] ";
            });
        });

        var logger = loggerFactory.CreateLogger<Program>();
        logger.LogInformation("Latte C# Driver Adapter starting...");
        logger.LogInformation("IPC Protocol Version: {Version}", Protocol.IpcProtocolVersion);

        // Read configuration from environment
        var socketPath = Environment.GetEnvironmentVariable("LATTE_DRIVER_SOCKET")
            ?? "/tmp/latte-driver.sock";
        var inflightStr = Environment.GetEnvironmentVariable("LATTE_DRIVER_INFLIGHT");
        var inflight = 512;
        if (!string.IsNullOrEmpty(inflightStr) && int.TryParse(inflightStr, out var parsed))
        {
            inflight = parsed;
        }

        logger.LogInformation("Socket path: {Path}", socketPath);
        logger.LogInformation("Max in-flight requests: {Inflight}", inflight);

        // Create session registry
        var sessions = new SessionRegistry(loggerFactory);

        // Create request handler
        var handler = new RequestHandler(sessions, loggerFactory.CreateLogger<RequestHandler>());

        // Create and run server
        using var server = new Server(socketPath, sessions, handler,
            loggerFactory.CreateLogger<Server>(), inflight);

        // Handle shutdown signals
        var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            logger.LogInformation("Received shutdown signal");
            cts.Cancel();
        };

        AppDomain.CurrentDomain.ProcessExit += (_, _) =>
        {
            logger.LogInformation("Process exit requested");
            cts.Cancel();
        };

        try
        {
            await server.RunAsync(cts.Token);
        }
        catch (OperationCanceledException)
        {
            logger.LogInformation("Server stopped");
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Server error");
            return 1;
        }

        logger.LogInformation("Latte C# Driver Adapter shutdown complete");
        return 0;
    }
}
