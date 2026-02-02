using System.Buffers;
using System.Net.Sockets;
using System.Threading.Channels;
using Microsoft.Extensions.Logging;

namespace LatteDriver;

/// <summary>
/// Unix domain socket server for the Latte driver adapter protocol.
/// </summary>
public class Server : IDisposable
{
    private readonly string _socketPath;
    private readonly SessionRegistry _sessions;
    private readonly RequestHandler _handler;
    private readonly ILogger _logger;
    private readonly SemaphoreSlim _inflightSemaphore;
    private readonly CancellationTokenSource _cts = new();
    private Socket? _listener;
    private bool _disposed;

    public Server(string socketPath, SessionRegistry sessions, RequestHandler handler,
        ILogger<Server> logger, int maxInflight = 512)
    {
        _socketPath = socketPath;
        _sessions = sessions;
        _handler = handler;
        _logger = logger;
        _inflightSemaphore = new SemaphoreSlim(maxInflight, maxInflight);
    }

    public async Task RunAsync(CancellationToken cancellationToken = default)
    {
        // Remove old socket file if exists
        if (File.Exists(_socketPath))
        {
            File.Delete(_socketPath);
        }

        // Ensure directory exists
        var dir = Path.GetDirectoryName(_socketPath);
        if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
        {
            Directory.CreateDirectory(dir);
        }

        _listener = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        _listener.Bind(new UnixDomainSocketEndPoint(_socketPath));

        // Set permissions to 666 (everyone can connect)
        File.SetUnixFileMode(_socketPath,
            UnixFileMode.UserRead | UnixFileMode.UserWrite |
            UnixFileMode.GroupRead | UnixFileMode.GroupWrite |
            UnixFileMode.OtherRead | UnixFileMode.OtherWrite);

        _listener.Listen(128);
        _logger.LogInformation("Server listening on {Path}", _socketPath);

        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _cts.Token);

        try
        {
            while (!linkedCts.Token.IsCancellationRequested)
            {
                var client = await _listener.AcceptAsync(linkedCts.Token);
                _logger.LogInformation("Client connected");
                _ = HandleClientAsync(client, linkedCts.Token);
            }
        }
        catch (OperationCanceledException)
        {
            _logger.LogInformation("Server shutting down");
        }
    }

    private async Task HandleClientAsync(Socket client, CancellationToken cancellationToken)
    {
        var responseChannel = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(1024)
        {
            SingleReader = true,
            SingleWriter = false
        });

        try
        {
            using var stream = new NetworkStream(client, ownsSocket: true);
            using var bufferedReader = new BufferedStream(stream, 64 * 1024);
            using var bufferedWriter = new BufferedStream(stream, 64 * 1024);

            // Start writer task
            var writerTask = Task.Run(async () =>
            {
                try
                {
                    await foreach (var response in responseChannel.Reader.ReadAllAsync(cancellationToken))
                    {
                        await bufferedWriter.WriteAsync(response, cancellationToken);
                        // Flush if no more items immediately available
                        if (!responseChannel.Reader.TryPeek(out _))
                        {
                            await bufferedWriter.FlushAsync(cancellationToken);
                        }
                    }
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    _logger.LogError(ex, "Writer task error");
                }
            }, cancellationToken);

            // Reader loop
            await ReaderLoopAsync(bufferedReader, responseChannel.Writer, cancellationToken);

            // Clean up writer
            responseChannel.Writer.Complete();
            await writerTask;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogError(ex, "Client handler error");
        }
        finally
        {
            _logger.LogInformation("Client disconnected");
        }
    }

    private async Task ReaderLoopAsync(Stream stream, ChannelWriter<byte[]> writer,
        CancellationToken cancellationToken)
    {
        var headerBuffer = new byte[Protocol.HeaderLength];
        var pendingTasks = new List<Task>();
        var frameCount = 0;
        const int CleanupInterval = 64;

        while (!cancellationToken.IsCancellationRequested)
        {
            // Read frame header
            var bytesRead = await ReadExactlyAsync(stream, headerBuffer.AsMemory(), cancellationToken);
            if (bytesRead == 0)
            {
                // Connection closed
                break;
            }

            // Parse header
            var version = headerBuffer[0];
            var flags = headerBuffer[1];
            var streamId = (short)((headerBuffer[2] << 8) | headerBuffer[3]);
            var opcode = headerBuffer[4];
            var bodyLength = (headerBuffer[5] << 24) | (headerBuffer[6] << 16) |
                             (headerBuffer[7] << 8) | headerBuffer[8];

            if (bodyLength > Protocol.MaxBodyLength)
            {
                _logger.LogError("Body length {Length} exceeds maximum", bodyLength);
                break;
            }

            // Read body using pooled buffer
            var body = bodyLength > 0 ? ArrayPool<byte>.Shared.Rent(bodyLength) : Array.Empty<byte>();
            if (bodyLength > 0)
            {
                bytesRead = await ReadExactlyAsync(stream, body.AsMemory(0, bodyLength), cancellationToken);
                if (bytesRead != bodyLength)
                {
                    ArrayPool<byte>.Shared.Return(body);
                    _logger.LogError("Incomplete body read: expected {Expected}, got {Actual}",
                        bodyLength, bytesRead);
                    break;
                }
            }

            var frame = new Frame
            {
                Version = version,
                Flags = flags,
                Stream = streamId,
                Opcode = opcode,
                Body = body.AsMemory(0, bodyLength)
            };

            // Dispatch request with backpressure
            await _inflightSemaphore.WaitAsync(cancellationToken);

            // Capture body for return to pool after processing
            var rentedBody = body;
            var task = Task.Run(async () =>
            {
                try
                {
                    var response = await _handler.HandleFrameAsync(frame);
                    await writer.WriteAsync(response, cancellationToken);
                }
                finally
                {
                    if (rentedBody.Length > 0)
                        ArrayPool<byte>.Shared.Return(rentedBody);
                    _inflightSemaphore.Release();
                }
            }, cancellationToken);

            // Track pending tasks and clean up completed ones periodically
            pendingTasks.Add(task);
            frameCount++;
            if (frameCount >= CleanupInterval)
            {
                pendingTasks.RemoveAll(t => t.IsCompleted);
                frameCount = 0;
            }
        }

        // Wait for pending tasks
        await Task.WhenAll(pendingTasks);
    }

    private static async Task<int> ReadExactlyAsync(Stream stream, Memory<byte> buffer,
        CancellationToken cancellationToken)
    {
        int totalRead = 0;
        while (totalRead < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.Slice(totalRead), cancellationToken);
            if (read == 0)
            {
                return totalRead; // EOF
            }
            totalRead += read;
        }
        return totalRead;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;

        _cts.Cancel();
        _listener?.Dispose();

        if (File.Exists(_socketPath))
        {
            try
            {
                File.Delete(_socketPath);
            }
            catch
            {
                // Ignore cleanup errors
            }
        }

        _sessions.Dispose();
        _inflightSemaphore.Dispose();
        _cts.Dispose();
    }
}
