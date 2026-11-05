using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace PubSub.Subscriber;

/// <summary>
/// Model pentru mesajele primite de la Broker.
/// </summary>
public class PubSubMessage
{
    [JsonPropertyName("id")]
    public string? Id { get; set; }

    [JsonPropertyName("topic")]
    public string? Topic { get; set; }

    [JsonPropertyName("payload")]
    public JsonElement Payload { get; set; }

    [JsonPropertyName("timestamp")]
    public string? Timestamp { get; set; }
}

/// <summary>
/// Model pentru Handshake-ul trimis la conectare.
/// </summary>
public class HandshakeMessage
{
    [JsonPropertyName("role")]
    public string Role { get; set; } = "subscriber";

    [JsonPropertyName("topic")]
    public string Topic { get; set; } = string.Empty;
}

/// <summary>
/// Client TCP Subscriber pentru sistemul Pub/Sub.
/// Gestionează conectarea, handshake-ul, ascultarea mesajelor și reconectarea automată cu backoff.
/// </summary>
public class SubscriberClient : IDisposable, IAsyncDisposable
{
    public string Host { get; }
    public int Port { get; }
    public string Topic { get; }

    public bool IsConnected => _tcpClient != null && _tcpClient.Connected && !_isDisposed;

    private TcpClient? _tcpClient;
    private NetworkStream? _networkStream;
    private StreamReader? _reader;
    private StreamWriter? _writer;

    private readonly int[] _backoffDelays;
    private readonly int _maxRetries;
    private readonly object _lock = new();
    private volatile bool _isDisposed;
    private volatile bool _isClosing;
    private CancellationTokenSource? _listenCts;

    public SubscriberClient(string topic, string host = "127.0.0.1", int port = 5050, int[]? backoffDelays = null)
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
    /// Inițiază conexiunea TCP la Broker și trimite Handshake-ul.
    /// Dacă Broker-ul nu este disponibil, încearcă reconectarea cu backoff (1s, 2s, 4s).
    /// </summary>
    public async Task<bool> ConnectAsync(CancellationToken cancellationToken = default)
    {
        CloseInternal();

        int totalAttempts = _maxRetries + 1;

        for (int attempt = 1; attempt <= totalAttempts; attempt++)
        {
            if (cancellationToken.IsCancellationRequested || _isClosing)
            {
                return false;
            }

            try
            {
                Console.WriteLine($"[REȚEA] Încercare de conectare la Broker ({Host}:{Port}) [încercarea {attempt}/{totalAttempts}]...");

                var client = new TcpClient();
                using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, timeoutCts.Token);

                await client.ConnectAsync(Host, Port, linkedCts.Token).ConfigureAwait(false);

                var stream = client.GetStream();
                var reader = new StreamReader(stream, Encoding.UTF8);
                var writer = new StreamWriter(stream, new UTF8Encoding(false)) { AutoFlush = true };

                lock (_lock)
                {
                    _tcpClient = client;
                    _networkStream = stream;
                    _reader = reader;
                    _writer = writer;
                    _isClosing = false;
                }

                Console.WriteLine($"[REȚEA] Conectat cu succes la Broker ({Host}:{Port})!");

                // Trimitere Handshake
                var handshake = new HandshakeMessage
                {
                    Role = "subscriber",
                    Topic = Topic
                };

                string handshakeJson = JsonSerializer.Serialize(handshake);
                await writer.WriteLineAsync(handshakeJson.AsMemory(), cancellationToken).ConfigureAwait(false);
                await writer.FlushAsync(cancellationToken).ConfigureAwait(false);

                Console.WriteLine($"[HANDSHAKE] Handshake trimis: role='subscriber', topic='{Topic}'");
                Console.WriteLine($"[ABONAT] Ascultare activă pentru mesaje pe topicul '{Topic}'...\n");

                return true;
            }
            catch (Exception ex) when (ex is SocketException or IOException or OperationCanceledException or TimeoutException)
            {
                CloseInternal();

                if (cancellationToken.IsCancellationRequested || _isClosing)
                {
                    return false;
                }

                Console.WriteLine($"[EROARE] Conectarea la Broker ({Host}:{Port}) a eșuat: {ex.Message}");

                if (attempt <= _maxRetries)
                {
                    int delay = _backoffDelays[attempt - 1];
                    Console.WriteLine($"[BACKOFF] Reîncercare de conectare în {delay}s...");
                    try
                    {
                        await Task.Delay(TimeSpan.FromSeconds(delay), cancellationToken).ConfigureAwait(false);
                    }
                    catch (OperationCanceledException)
                    {
                        return false;
                    }
                }
                else
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine($"[EROARE CRITICĂ] S-au epuizat toate încercările de conectare cu backoff (1s, 2s, 4s). Broker-ul este momentan indisponibil.");
                    Console.ResetColor();
                    return false;
                }
            }
        }

        return false;
    }

    /// <summary>
    /// Varianta sincronă a metodei Connect (conform cerinței din temă).
    /// </summary>
    public bool Connect(CancellationToken cancellationToken = default)
    {
        return ConnectAsync(cancellationToken).GetAwaiter().GetResult();
    }

    /// <summary>
    /// Bucla principală de ascultare pe un Task/Thread dedicat.
    /// Citește mesaje linie cu linie, le parsează cu System.Text.Json și le afișează în consolă.
    /// Dacă se detectează deconectarea Broker-ului, inițiază automat ReconnectAsync.
    /// </summary>
    public async Task ListenAsync(CancellationToken cancellationToken = default)
    {
        _listenCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var token = _listenCts.Token;

        var jsonOptions = new JsonSerializerOptions
        {
            PropertyNameCaseInsensitive = true,
            WriteIndented = false
        };

        while (!token.IsCancellationRequested && !_isClosing && !_isDisposed)
        {
            if (!IsConnected)
            {
                Console.WriteLine("[AVERTISMENT] Conexiunea nu este activă. Se încearcă reconectarea...");
                bool reconnected = await ReconnectAsync(token).ConfigureAwait(false);
                if (!reconnected)
                {
                    Console.ForegroundColor = ConsoleColor.Yellow;
                    Console.WriteLine("[STOP] Ascultarea s-a oprit din cauza indisponibilității Broker-ului.");
                    Console.ResetColor();
                    break;
                }
                continue;
            }

            StreamReader? reader;
            lock (_lock)
            {
                reader = _reader;
            }

            if (reader == null)
            {
                break;
            }

            string? line;
            try
            {
                line = await reader.ReadLineAsync(token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // Închidere intenționată via token
                break;
            }
            catch (Exception ex) when (ex is IOException or SocketException or ObjectDisposedException)
            {
                if (_isClosing || _isDisposed || token.IsCancellationRequested)
                {
                    break;
                }

                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine($"\n[AVERTISMENT] Conexiunea cu Broker-ul s-a întrerupt: {ex.Message}");
                Console.ResetColor();

                bool reconnected = await ReconnectAsync(token).ConfigureAwait(false);
                if (!reconnected)
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[EROARE] Nu s-a putut restabili conexiunea. Ascultarea este oprită.");
                    Console.ResetColor();
                    break;
                }
                continue;
            }

            // Dacă readLine returnează null, Broker-ul a închis conexiunea (EOF)
            if (line == null)
            {
                if (_isClosing || _isDisposed || token.IsCancellationRequested)
                {
                    break;
                }

                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine($"\n[AVERTISMENT] Broker-ul a închis conexiunea (EOF primit).");
                Console.ResetColor();

                bool reconnected = await ReconnectAsync(token).ConfigureAwait(false);
                if (!reconnected)
                {
                    Console.ForegroundColor = ConsoleColor.Red;
                    Console.WriteLine("[EROARE] Nu s-a putut restabili conexiunea cu Broker-ul.");
                    Console.ResetColor();
                    break;
                }
                continue;
            }

            if (string.IsNullOrWhiteSpace(line))
            {
                continue;
            }

            // Parsare JSON mesaj
            try
            {
                var message = JsonSerializer.Deserialize<PubSubMessage>(line, jsonOptions);
                if (message != null)
                {
                    DisplayMessage(message);
                }
            }
            catch (JsonException jsonEx)
            {
                Console.ForegroundColor = ConsoleColor.DarkYellow;
                Console.WriteLine($"[AVERTISMENT JSON] Mesaj invalid primit de la Broker: {jsonEx.Message}");
                Console.WriteLine($"  Conținut brut: {line}");
                Console.ResetColor();
            }
        }
    }

    /// <summary>
    /// Varianta sincronă a metodei Listen (conform cerinței din temă).
    /// </summary>
    public void Listen(CancellationToken cancellationToken = default)
    {
        ListenAsync(cancellationToken).GetAwaiter().GetResult();
    }

    /// <summary>
    /// Reîncearcă reconectarea automată folosind backoff (1s, 2s, 4s).
    /// </summary>
    public async Task<bool> ReconnectAsync(CancellationToken cancellationToken = default)
    {
        Console.WriteLine($"[RECONECTARE] Inițiere reconectare automată la Broker ({Host}:{Port}) pentru topicul '{Topic}'...");
        CloseInternal();
        return await ConnectAsync(cancellationToken).ConfigureAwait(false);
    }

    /// <summary>
    /// Varianta sincronă a metodei Reconnect (conform cerinței din temă).
    /// </summary>
    public bool Reconnect(CancellationToken cancellationToken = default)
    {
        return ReconnectAsync(cancellationToken).GetAwaiter().GetResult();
    }

    /// <summary>
    /// Formatează și afișează mesajul primit în consolă.
    /// </summary>
    private void DisplayMessage(PubSubMessage msg)
    {
        string payloadFormatted;
        try
        {
            payloadFormatted = JsonSerializer.Serialize(msg.Payload, new JsonSerializerOptions { WriteIndented = false });
        }
        catch
        {
            payloadFormatted = msg.Payload.ToString() ?? "{}";
        }

        Console.ForegroundColor = ConsoleColor.Green;
        Console.WriteLine("------------------------------------------------------------");
        Console.WriteLine($"[MESAJ PRIMIT] Topic: {msg.Topic}");
        Console.ResetColor();
        Console.WriteLine($"  Timestamp: {msg.Timestamp}");
        if (!string.IsNullOrEmpty(msg.Id))
        {
            Console.WriteLine($"  ID:        {msg.Id}");
        }
        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine($"  Payload:   {payloadFormatted}");
        Console.ResetColor();
        Console.WriteLine("------------------------------------------------------------\n");
    }

    /// <summary>
    /// Închide curat socketul și fluxurile de rețea.
    /// Nu aruncă excepții și previne blocarea altor componente ale sistemului.
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

        CloseInternal();
    }

    private void CloseInternal()
    {
        lock (_lock)
        {
            try
            {
                _writer?.Dispose();
            }
            catch
            {
                // Ignorat
            }
            _writer = null;

            try
            {
                _reader?.Dispose();
            }
            catch
            {
                // Ignorat
            }
            _reader = null;

            try
            {
                _networkStream?.Dispose();
            }
            catch
            {
                // Ignorat
            }
            _networkStream = null;

            if (_tcpClient != null)
            {
                try
                {
                    if (_tcpClient.Client != null && _tcpClient.Connected)
                    {
                        _tcpClient.Client.Shutdown(SocketShutdown.Both);
                    }
                }
                catch
                {
                    // Socket-ul putea fi deja închis de cealaltă parte
                }

                try
                {
                    _tcpClient.Close();
                    _tcpClient.Dispose();
                }
                catch
                {
                    // Ignorat
                }

                _tcpClient = null;
            }
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
