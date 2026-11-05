using Grpc.Core;
using Grpc.Net.Client;
using Pubsub;

namespace PubSub.Subscriber;

/// <summary>
/// Client gRPC Subscriber pentru sistemul Pub/Sub.
/// Apelează RPC-ul server-streaming Subscribe(SubRequest) și ascultă continuu mesajele.
/// Include reconectare automată cu backoff (1s, 2s, 4s) și închidere curată (graceful shutdown).
/// </summary>
public class SubscriberGrpcClient : IDisposable, IAsyncDisposable
{
    public string Host { get; }
    public int Port { get; }
    public string Topic { get; }

    private readonly int[] _backoffDelays;
    private readonly int _maxRetries;
    private readonly object _lock = new();
    private GrpcChannel? _channel;
    private Broker.BrokerClient? _client;
    private CancellationTokenSource? _listenCts;
    private volatile bool _isClosing;
    private volatile bool _isDisposed;

    public SubscriberGrpcClient(string topic, string host = "127.0.0.1", int port = 5051, int[]? backoffDelays = null)
    {
        if (string.IsNullOrWhiteSpace(topic))
        {
            throw new ArgumentException("Numele topicului nu poate fi gol.", nameof(topic));
        }

        Topic = topic.Trim();
        Host = host;
        Port = port;
        _backoffDelays = backoffDelays ?? new[] { 1, 2, 4 };
        _maxRetries = _backoffDelays.Length;
    }

    /// <summary>
    /// Bucla principală de ascultare gRPC server-streaming.
    /// Inițiază stream-ul Subscribe, procesează mesajele și gestionează reconectarea automată cu backoff.
    /// </summary>
    public async Task ListenAsync(CancellationToken cancellationToken = default)
    {
        _listenCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var token = _listenCts.Token;

        int consecutiveFailures = 0;

        while (!token.IsCancellationRequested && !_isClosing && !_isDisposed)
        {
            try
            {
                EnsureChannel();

                Console.WriteLine($"[gRPC] Conectare și abonare la Broker ({Host}:{Port}) pentru topicul '{Topic}'...");

                var request = new SubRequest { Topic = Topic };
                using var call = _client!.Subscribe(request, cancellationToken: token);

                Console.WriteLine($"[gRPC] Stream deschis cu succes!");
                Console.WriteLine($"[ABONAT] Ascultare activă pentru mesaje gRPC pe topicul '{Topic}'...\n");

                // La fiecare mesaj receptionat cu succes resetam contorul de erori
                await foreach (var message in call.ResponseStream.ReadAllAsync(token).ConfigureAwait(false))
                {
                    consecutiveFailures = 0;
                    DisplayMessage(message);
                }

                // Dacă stream-ul s-a încheiat fără excepție
                if (_isClosing || _isDisposed || token.IsCancellationRequested)
                {
                    break;
                }

                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("\n[AVERTISMENT gRPC] Stream-ul a fost închis de Broker.");
                Console.ResetColor();
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested || _isClosing)
            {
                // Închidere intenționată via token/Ctrl+C
                break;
            }
            catch (RpcException rpcEx) when (rpcEx.StatusCode == StatusCode.Cancelled && (token.IsCancellationRequested || _isClosing))
            {
                // Închidere intenționată via gRPC cancel
                break;
            }
            catch (Exception ex) when (ex is RpcException or HttpRequestException or IOException or TimeoutException)
            {
                if (_isClosing || _isDisposed || token.IsCancellationRequested)
                {
                    break;
                }

                consecutiveFailures++;
                string errorDetail = ex is RpcException r ? $"{r.StatusCode}: {r.Status.Detail}" : ex.Message;

                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine($"\n[AVERTISMENT gRPC] Conexiunea cu Broker-ul a eșuat ({consecutiveFailures}/{_maxRetries + 1}): {errorDetail}");
                Console.ResetColor();

                if (consecutiveFailures <= _maxRetries)
                {
                    int delay = _backoffDelays[consecutiveFailures - 1];
                    Console.WriteLine($"[BACKOFF] Reîncercare de conectare în {delay}s...");
                    try
                    {
                        await Task.Delay(TimeSpan.FromSeconds(delay), token).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        break;
                    }
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine($"[EROARE CRITICĂ] S-au epuizat toate încercările de reconectare cu backoff (1s, 2s, 4s). Broker-ul gRPC este momentan indisponibil.");
                    Console.ResetColor();
                    break;
                }
            }
        }
    }

    /// <summary>
    /// Varianta sincronă a metodei Listen.
    /// </summary>
    public void Listen(CancellationToken cancellationToken = default)
    {
        ListenAsync(cancellationToken).GetAwaiter().GetResult();
    }

    private void EnsureChannel()
    {
        lock (_lock)
        {
            if (_channel == null || _client == null)
            {
                string address = $"http://{Host}:{Port}";
                _channel = GrpcChannel.ForAddress(address);
                _client = new Broker.BrokerClient(_channel);
            }
        }
    }

    /// <summary>
    /// Afișează mesajul primit prin gRPC în consolă, identic ca format cu varianta TCP.
    /// </summary>
    private void DisplayMessage(Message msg)
    {
        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("------------------------------------------------------------");
        Console.WriteLine($"[MESAJ PRIMIT (gRPC)] Topic: {msg.Topic}");
        Console.ResetColor();
        Console.WriteLine($"  Timestamp: {msg.Timestamp}");
        if (!string.IsNullOrEmpty(msg.Id))
        {
            Console.WriteLine($"  ID:        {msg.Id}");
        }
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine($"  Payload:   {msg.Payload}");
        Console.ResetColor();
        Console.WriteLine("------------------------------------------------------------\n");
    }

    /// <summary>
    /// Închide curat stream-ul gRPC și canalul HTTP/2, notificând brokerul să elibereze resursele.
    /// </summary>
    public void Close()
    {
        _isClosing = true;
        try
        {
            _listenCts?.Cancel();
        }
        catch
        {
            // Ignorat la închidere
        }

        lock (_lock)
        {
            try
            {
                _channel?.Dispose();
            }
            catch
            {
                // Ignorat
            }
            _channel = null;
            _client = null;
        }
    }

    public void Dispose()
    {
        if (_isDisposed) return;
        _isDisposed = true;
        Close();
        try
        {
            _listenCts?.Dispose();
        }
        catch
        {
            // Ignorat
        }
        GC.SuppressFinalize(this);
    }

    public ValueTask DisposeAsync()
    {
        Dispose();
        return ValueTask.CompletedTask;
    }
}
