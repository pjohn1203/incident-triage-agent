package com.example.triage;

import com.example.triage.agents.TriagePipeline;
import com.example.triage.data.IncidentData;
import com.example.triage.eval.EvalReport;
import com.example.triage.eval.EvalRunner;
import com.example.triage.eval.GoldenCase;
import com.example.triage.models.ModelProvider;
import com.example.triage.runtime.AdkTriager;
import com.example.triage.runtime.TriageResult;
import com.example.triage.server.TriageServer;
import com.example.triage.telemetry.Telemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

/**
 * Command-line entry point.
 *
 * <pre>
 * ask   "incident text"        triage one incident and print the report
 * serve                        start the HTTP API
 * eval  [--golden path] [--out path] [--min-pass-rate 0.9] [--parallelism 1]
 * </pre>
 *
 * Add {@code --model gemini-3.8-flash} (with GOOGLE_API_KEY set) to use a real model.
 */
public final class App {

  private App() {}

  public static void main(String[] args) throws Exception {
    // Must run before any ADK class is used; see Telemetry's class comment.
    Telemetry telemetry = Telemetry.init(Telemetry.Settings.fromEnv(System.getenv()));

    List<String> positional = new ArrayList<>();
    Map<String, String> flags = parseFlags(args, positional);
    if (positional.isEmpty() || flags.containsKey("help")) {
      printUsage();
      telemetry.shutdown();
      return;
    }
    String command = positional.remove(0);
    AppConfig config = AppConfig.from(System.getenv(), flags);

    if (command.equals("serve")) {
      serve(config, telemetry); // returns after Ctrl-C; the shutdown hook flushes telemetry
      return;
    }
    int exitCode =
        switch (command) {
          case "ask" -> ask(config, telemetry, String.join(" ", positional));
          case "eval" -> eval(config, telemetry, flags);
          default -> {
            System.err.println("Unknown command: " + command);
            printUsage();
            yield 2;
          }
        };
    telemetry.shutdown();
    System.exit(exitCode);
  }

  static AdkTriager buildTriager(AppConfig config, Telemetry telemetry) {
    ModelProvider models =
        new ModelProvider(config.model(), config.modelOverrides(), config.simulatedLatencyMs());
    return new AdkTriager(
        TriagePipeline.build(IncidentData.loadDefault(), models),
        TriagePipeline.TRIAGE_LEAD,
        models.label(),
        telemetry.tracer(),
        config.maxLlmCalls());
  }

  private static int ask(AppConfig config, Telemetry telemetry, String incident) {
    if (incident.isBlank()) {
      System.err.println("Usage: ask \"<incident description>\"");
      return 2;
    }
    try {
      TriageResult result = buildTriager(config, telemetry).triage(incident, config.deadline());
      System.out.println(Json.pretty(result.toJson()));
      return result.report() == null ? 1 : 0;
    } catch (TimeoutException e) {
      System.err.println("Timed out after " + config.deadline().toMillis() + "ms");
      return 1;
    }
  }

  private static void serve(AppConfig config, Telemetry telemetry) throws Exception {
    TriageServer server =
        new TriageServer(
            buildTriager(config, telemetry),
            new TriageServer.Config(
                config.port(),
                config.maxConcurrent(),
                config.queueWait(),
                config.deadline(),
                /* maxIncidentChars= */ 4_000),
            telemetry.tracer());
    server.start();
    System.out.printf(
        "Serving on http://localhost:%d  (model=%s, max_concurrent=%d, deadline=%dms, traces=%s)%n",
        server.port(),
        config.model(),
        config.maxConcurrent(),
        config.deadline().toMillis(),
        telemetry.exportTarget());

    CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.close();
                  telemetry.shutdown();
                  stopped.countDown();
                }));
    stopped.await();
  }

  private static int eval(AppConfig config, Telemetry telemetry, Map<String, String> flags)
      throws Exception {
    Path golden = Path.of(flags.getOrDefault("golden", "evals/golden.jsonl"));
    Path out = Path.of(flags.getOrDefault("out", "target/eval-report.json"));
    double minPassRate = Double.parseDouble(flags.getOrDefault("min-pass-rate", "0.9"));
    int parallelism = Integer.parseInt(flags.getOrDefault("parallelism", "1"));

    List<GoldenCase> cases = GoldenCase.load(golden);
    AdkTriager triager = buildTriager(config, telemetry);
    EvalReport report =
        EvalRunner.run(triager, triager.modelLabel(), cases, config.deadline(), parallelism);

    report.print(System.out);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    Files.writeString(out, Json.pretty(report.toJson()));
    System.out.println("Full report: " + out);

    if (report.passRate() + 1e-9 < minPassRate) {
      System.out.printf("FAILED: pass rate %.2f is below the %.2f gate%n", report.passRate(), minPassRate);
      return 1;
    }
    return 0;
  }

  /** Parses {@code --key value} and {@code --flag} arguments; everything else is positional. */
  static Map<String, String> parseFlags(String[] args, List<String> positional) {
    Map<String, String> flags = new HashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--")) {
        String key = arg.substring(2);
        int eq = key.indexOf('=');
        if (eq >= 0) {
          flags.put(key.substring(0, eq), key.substring(eq + 1));
        } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
          flags.put(key, args[++i]);
        } else {
          flags.put(key, "true");
        }
      } else {
        positional.add(arg);
      }
    }
    return flags;
  }

  private static void printUsage() {
    System.out.println(
        """
        Usage: java -jar target/incident-triage-agent.jar <command> [options]

        Commands:
          ask "<incident>"   Triage one incident and print the JSON result
          serve              Start the HTTP API (POST /v1/triage, GET /healthz, GET /metrics)
          eval               Score the pipeline on evals/golden.jsonl

        Common options:
          --model <id>       baseline (default, offline) or a Gemini model id, e.g. gemini-3.8-flash
          --lead-model <id>  Use a different model for the triage lead only

        Eval options:
          --golden <path>  --out <path>  --min-pass-rate <0..1>  --parallelism <n>

        See README.md for environment variables.""");
  }
}
