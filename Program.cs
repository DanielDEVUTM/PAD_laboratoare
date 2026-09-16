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
        string host = "127.0.0.1";
        int port = 5050;

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

        Console.ForegroundColor = ConsoleColor.Cyan;
        Console.WriteLine("============================================================");
        Console.WriteLine("           CLIENT SUBSCRIBER PUB/SUB (.NET C#)              ");
        Console.WriteLine("============================================================");
        Console.ResetColor();
        Console.WriteLine($"Broker:   {host}:{port}");
        Console.WriteLine($"Topic:    {topic}");
        Console.WriteLine("Apasă Ctrl+C pentru a opri clientul în mod curat.\n");

        using var cts = new CancellationTokenSource();
        using var client = new SubscriberClient(topic, host, port);

        // Înregistrare handler pentru închidere curată la Ctrl+C / SIGINT
        Console.CancelKeyPress += (sender, eventArgs) =>
        {
            // Oprim terminarea imediată a procesului pentru a permite închiderea curată a resurselor
            eventArgs.Cancel = true;
            Console.ForegroundColor = ConsoleColor.Yellow;
            Console.WriteLine("\n[SEMNAL] Semnal de oprire primit (Ctrl+C). Se închide socketul curat...");
            Console.ResetColor();

            cts.Cancel();
            client.Close();
        };

        // Înregistrare hook suplimentar pentru ieșirea din proces
        AppDomain.CurrentDomain.ProcessExit += (sender, eventArgs) =>
        {
            client.Close();
        };

        try
        {
            // Pas 1: Conectare inițială cu backoff
            bool connected = await client.ConnectAsync(cts.Token);
            if (!connected)
            {
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine($"[EROARE CRITICĂ] Nu s-a putut stabili conexiunea inițială cu Broker-ul. Aplicația se va opri.");
                Console.ResetColor();
                return 1;
            }

            // Pas 2: Rulare buclă de ascultare pe un Task dedicat
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
            Console.WriteLine("[STOP] Socket închis curat. Subscriber deconectat fără erori.");
            Console.ResetColor();
        }

        return 0;
    }

    private static void PrintUsage()
    {
        Console.WriteLine("\nUtilizare:");
        Console.WriteLine("  dotnet run --topic <nume_topic> [--host <adresa_ip>] [--port <port>]");
        Console.WriteLine("\nExemple:");
        Console.WriteLine("  dotnet run --topic sport");
        Console.WriteLine("  dotnet run --topic stiri --host 127.0.0.1 --port 5050");
        Console.WriteLine("  dotnet run --topic meteo");
        Console.WriteLine();
    }
}
