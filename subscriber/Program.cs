using System.Text;
using PubSub.Subscriber;

namespace PubSub.Subscriber;

internal class Program
{
    private static async Task<int> Main(string[] args)
    {
        // Asigurare compatibilitate encodare UTF-8 în consolă pe Windows
        Console.OutputEncoding = Encoding.UTF8;

        string? topic = null;
        string mode = "tcp";
        string host = "127.0.0.1";
        int? port = null;

        // Parsare argumente linie de comandă
        for (int i = 0; i < args.Length; i++)
        {
            string arg = args[i];

            if (arg.Equals("--topic", StringComparison.OrdinalIgnoreCase) || arg.Equals("-t", StringComparison.OrdinalIgnoreCase))
            {
                if (i + 1 < args.Length)
                {
                    topic = args[++i];
                }
            }
            else if (arg.StartsWith("--topic=", StringComparison.OrdinalIgnoreCase))
            {
                topic = arg.Substring("--topic=".Length);
            }
            else if (arg.Equals("--mode", StringComparison.OrdinalIgnoreCase) || arg.Equals("-m", StringComparison.OrdinalIgnoreCase))
            {
                if (i + 1 < args.Length)
                {
                    mode = args[++i].ToLowerInvariant();
                }
            }
            else if (arg.StartsWith("--mode=", StringComparison.OrdinalIgnoreCase))
            {
                mode = arg.Substring("--mode=".Length).ToLowerInvariant();
            }
            else if (arg.Equals("--host", StringComparison.OrdinalIgnoreCase))
            {
                if (i + 1 < args.Length)
                {
                    host = args[++i];
                }
            }
            else if (arg.StartsWith("--host=", StringComparison.OrdinalIgnoreCase))
            {
                host = arg.Substring("--host=".Length);
            }
            else if (arg.Equals("--port", StringComparison.OrdinalIgnoreCase) || arg.Equals("-p", StringComparison.OrdinalIgnoreCase))
            {
                if (i + 1 < args.Length && int.TryParse(args[++i], out int parsedPort))
                {
                    port = parsedPort;
                }
            }
            else if (arg.StartsWith("--port=", StringComparison.OrdinalIgnoreCase))
            {
                if (int.TryParse(arg.Substring("--port=".Length), out int parsedPort))
                {
                    port = parsedPort;
                }
            }
            else if (arg.Equals("--help", StringComparison.OrdinalIgnoreCase) || arg.Equals("-h", StringComparison.OrdinalIgnoreCase))
            {
                PrintUsage();
                return 0;
            }
        }

        if (string.IsNullOrWhiteSpace(topic))
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine("[EROARE] Parametrul --topic este obligatoriu!");
            Console.ResetColor();
            PrintUsage();
            return 1;
        }

        if (mode != "tcp" && mode != "grpc")
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[EROARE] Mod necunoscut: '{mode}'. Modurile valide sunt 'tcp' sau 'grpc'.");
            Console.ResetColor();
            PrintUsage();
            return 1;
        }

        // Port implicit: 5050 pentru TCP, 5051 pentru gRPC (conform BrokerServer.java)
        int effectivePort = port ?? (mode == "tcp" ? 5050 : 5051);

        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("============================================================");
        Console.WriteLine($"      CLIENT SUBSCRIBER PUB/SUB (.NET C# - Mod: {mode.ToUpper()})");
        Console.WriteLine("============================================================");
        Console.ResetColor();
        Console.WriteLine($"Transport: {mode.ToUpper()}");
        Console.WriteLine($"Broker:    {host}:{effectivePort}");
        Console.WriteLine($"Topic:     {topic}");
        Console.WriteLine("Apasă Ctrl+C pentru a opri clientul în mod curat.\n");

        using var cts = new CancellationTokenSource();

        if (mode == "grpc")
        {
            return await RunGrpcSubscriberAsync(topic, host, effectivePort, cts);
        }
        else
        {
            return await RunTcpSubscriberAsync(topic, host, effectivePort, cts);
        }
    }

    private static async Task<int> RunTcpSubscriberAsync(string topic, string host, int port, CancellationTokenSource cts)
    {
        using var client = new SubscriberClient(topic, host, port);

        Console.CancelKeyPress += (sender, eventArgs) =>
        {
            eventArgs.Cancel = true;
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine("\n[SEMNAL] Semnal de oprire primit (Ctrl+C). Se închide conexiunea TCP curat...");
            Console.ResetColor();

            cts.Cancel();
            client.Close();
        };

        AppDomain.CurrentDomain.ProcessExit += (sender, eventArgs) =>
        {
            client.Close();
        };

        try
        {
            bool connected = await client.ConnectAsync(cts.Token);
            if (!connected)
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"[EROARE CRITICĂ] Nu s-a putut stabili conexiunea inițială TCP cu Broker-ul. Aplicația se va opri.");
                Console.ResetColor();
                return 1;
            }

            Task listenTask = Task.Run(() => client.ListenAsync(cts.Token), cts.Token);
            await listenTask;
        }
        catch (OperationCanceledException)
        {
            // Oprire normală prin CancellationToken
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[EROARE NEAȘTEPTATĂ] {ex.Message}");
            Console.ResetColor();
        }
        finally
        {
            client.Close();
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("[STOP] Socket TCP închis curat. Subscriber deconectat fără erori.");
            Console.ResetColor();
        }

        return 0;
    }

    private static async Task<int> RunGrpcSubscriberAsync(string topic, string host, int port, CancellationTokenSource cts)
    {
        using var client = new SubscriberGrpcClient(topic, host, port);

        Console.CancelKeyPress += (sender, eventArgs) =>
        {
            eventArgs.Cancel = true;
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine("\n[SEMNAL] Semnal de oprire primit (Ctrl+C). Se închide stream-ul gRPC curat...");
            Console.ResetColor();

            cts.Cancel();
            client.Close();
        };

        AppDomain.CurrentDomain.ProcessExit += (sender, eventArgs) =>
        {
            client.Close();
        };

        try
        {
            Task listenTask = Task.Run(() => client.ListenAsync(cts.Token), cts.Token);
            await listenTask;
        }
        catch (OperationCanceledException)
        {
            // Oprire normală prin CancellationToken
        }
        catch (Exception ex)
        {
            Console.ForegroundColor = ConsoleColor.Red;
            Console.WriteLine($"[EROARE NEAȘTEPTATĂ gRPC] {ex.Message}");
            Console.ResetColor();
        }
        finally
        {
            client.Close();
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("[STOP] Stream gRPC închis curat. Subscriber deconectat fără erori.");
            Console.ResetColor();
        }

        return 0;
    }

    private static void PrintUsage()
    {
        Console.WriteLine("\nUtilizare:");
        Console.WriteLine("  dotnet run --topic <nume_topic> [--mode {tcp,grpc}] [--host <adresa_ip>] [--port <port>]");
        Console.WriteLine("\nExemple Mod TCP (implicit, port 5050):");
        Console.WriteLine("  dotnet run --topic sport");
        Console.WriteLine("  dotnet run --topic stiri --mode tcp");
        Console.WriteLine("\nExemple Mod gRPC (port 5051):");
        Console.WriteLine("  dotnet run --topic sport --mode grpc");
        Console.WriteLine("  dotnet run --topic stiri --mode grpc --port 5051");
        Console.WriteLine();
    }
}
