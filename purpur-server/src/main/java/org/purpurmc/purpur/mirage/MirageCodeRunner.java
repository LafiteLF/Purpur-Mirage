package org.purpurmc.purpur.mirage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MirageCodeRunner — Execute Python and C++ code from within the server.
 *
 * Architecture:
 * - Python: Uses GraalVM Polyglot API (org.graalvm.polyglot.Context) which is
 *   bundled with GraalVM JDK 25 (the required JDK for Mirage). Zero external deps.
 *   Falls back to system python if GraalVM polyglot is unavailable.
 * - C++: Transpiles C++ code to Java at runtime and compiles with the JDK's
 *   built-in javax.tools.JavaCompiler. Zero external dependencies.
 *
 * Features:
 * - /python <code> — Execute inline Python code
 * - /c++ <code> — Execute inline C++ code (transpile to Java + compile + run)
 * - Auto-run: Monitor ./python/ and ./c++/ directories for main.py/main.cpp
 */
public final class MirageCodeRunner {

    private static final Logger LOGGER = Logger.getLogger("Mirage");

    private static File serverRoot;

    // Auto-run directories
    private static final String PYTHON_DIR = "python";
    private static final String CPP_DIR = "c++";
    private static final String PYTHON_MAIN = "main.py";
    private static final String CPP_MAIN = "main.cpp";

    // File modification tracking
    private static long pythonMainLastModified = 0;
    private static long cppMainLastModified = 0;

    // Statistics
    private static final AtomicLong totalPythonRuns = new AtomicLong(0);
    private static final AtomicLong totalCppRuns = new AtomicLong(0);
    private static final AtomicLong totalAutoRuns = new AtomicLong(0);
    private static final AtomicLong totalFailures = new AtomicLong(0);

    // GraalVM polyglot context (lazy-initialized)
    private static volatile Object graalPythonContext; // org.graalvm.polyglot.Context
    private static volatile boolean graalPolyglotAvailable = false;
    private static volatile boolean graalPolyglotChecked = false;

    // Watch service for file monitoring
    private static WatchService watchService;
    private static Thread watchThread;

    private MirageCodeRunner() {}

    // ========== Initialization ==========

    public static void init(File root) {
        serverRoot = root;

        if (!MirageConfig.enableCodeRunner) {
            LOGGER.info("[Mirage] Code runner disabled in config.");
            return;
        }

        // Create auto-run directories
        new File(root, PYTHON_DIR).mkdirs();
        new File(root, CPP_DIR).mkdirs();

        // Check GraalVM polyglot availability
        checkGraalPolyglot();

        LOGGER.info("[Mirage] Code runner initialized. Directories: ./" + PYTHON_DIR + "/ and ./" + CPP_DIR + "/");

        // Run existing main files on startup
        checkAndRunAutoFiles(0);

        // Start file watcher
        startFileWatcher();
    }

    /**
     * Check if GraalVM Polyglot API is available for Python execution.
     */
    private static void checkGraalPolyglot() {
        if (graalPolyglotChecked) return;
        graalPolyglotChecked = true;

        try {
            // Try to load the GraalVM Polyglot Context class
            Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context");
            Class<?> engineClass = Class.forName("org.graalvm.polyglot.Engine");

            // Try to create a test context to verify Python is available
            Object engine = engineClass.getMethod("newBuilder").invoke(null);
            Object engineBuilt = engineClass.getMethod("build").invoke(engine);

            Object contextBuilder = contextClass.getMethod("newBuilder").invoke(null);
            // Configure allowed languages
            contextClass.getMethod("allowAllAccess", boolean.class).invoke(contextBuilder, true);
            Object context = contextClass.getMethod("build").invoke(contextBuilder);

            // Test if Python is available
            contextClass.getMethod("initialize", String.class).invoke(context, "python");

            graalPythonContext = context;
            graalPolyglotAvailable = true;
            LOGGER.info("[Mirage] GraalVM Polyglot Python engine available — using embedded Python runtime.");
        } catch (Throwable e) {
            graalPolyglotAvailable = false;
            LOGGER.info("[Mirage] GraalVM Polyglot not available, will use system python: " + MirageConfig.pythonExecutable);
        }
    }

    // ========== Python Execution ==========

    /**
     * Execute Python code using GraalVM Polyglot or system python.
     */
    public static void executePython(String code, Consumer<String> callback) {
        if (!MirageConfig.enableCodeRunner) {
            callback.accept("Code runner is disabled.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            if (graalPolyglotAvailable) {
                executePythonGraal(code, callback);
            } else {
                executePythonSystem(code, callback);
            }
        });
    }

    /**
     * Execute Python using GraalVM Polyglot API (embedded, no external python needed).
     */
    private static void executePythonGraal(String code, Consumer<String> callback) {
        try {
            Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context");
            Object context = graalPythonContext;

            // Create a new context for each execution to avoid state leakage
            Object builder = contextClass.getMethod("newBuilder").invoke(null);
            contextClass.getMethod("allowAllAccess", boolean.class).invoke(builder, true);

            // Set up output capture
            StringWriter stdoutWriter = new StringWriter();
            StringWriter stderrWriter = new StringWriter();
            contextClass.getMethod("out", java.io.OutputStream.class).invoke(builder,
                new java.io.OutputStream() {
                    @Override
                    public void write(int b) { stdoutWriter.write(b); }
                });
            contextClass.getMethod("err", java.io.OutputStream.class).invoke(builder,
                new java.io.OutputStream() {
                    @Override
                    public void write(int b) { stderrWriter.write(b); }
                });

            Object ctx = contextClass.getMethod("build").invoke(builder);

            // Initialize Python
            contextClass.getMethod("initialize", String.class).invoke(ctx, "python");

            // Execute the code
            Object bindings = contextClass.getMethod("getBindings", String.class).invoke(ctx, "python");
            // Evaluate the code
            contextClass.getMethod("eval", String.class, String.class).invoke(ctx, "python", code);

            // Close context
            contextClass.getMethod("close").invoke(ctx);

            String stdout = stdoutWriter.toString().trim();
            String stderr = stderrWriter.toString().trim();

            totalPythonRuns.incrementAndGet();

            if (stderr.isEmpty()) {
                callback.accept(stdout.isEmpty() ? "(no output)" : stdout);
            } else {
                callback.accept(stdout + (stdout.isEmpty() ? "" : "\n") + "[stderr]\n" + stderr);
            }
        } catch (Throwable e) {
            totalFailures.incrementAndGet();
            callback.accept("Python error: " + e.getMessage());
        }
    }

    /**
     * Execute Python using system-installed python (fallback).
     */
    private static void executePythonSystem(String code, Consumer<String> callback) {
        File tempFile = null;
        try {
            tempFile = File.createTempFile("mirage_python_", ".py");
            Files.writeString(tempFile.toPath(), code, StandardCharsets.UTF_8);

            String output = runProcess(new ProcessBuilder(
                MirageConfig.pythonExecutable, tempFile.getAbsolutePath()),
                MirageConfig.codeExecutionTimeoutSec);
            totalPythonRuns.incrementAndGet();
            callback.accept(output);
        } catch (Exception e) {
            totalFailures.incrementAndGet();
            callback.accept("Error: " + e.getMessage() +
                "\nPython not found. Install Python or use GraalVM JDK.");
        } finally {
            if (tempFile != null) tempFile.delete();
        }
    }

    // ========== C++ Execution (via Java transpilation) ==========

    /**
     * Execute C++ code by transpiling to Java and compiling with the JDK's
     * built-in JavaCompiler. No external C++ compiler required.
     */
    public static void executeCpp(String code, Consumer<String> callback) {
        if (!MirageConfig.enableCodeRunner) {
            callback.accept("Code runner is disabled.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Transpile C++ to Java
                String javaCode = CppToJavaTranspiler.transpile(code);
                LOGGER.fine("[Mirage] C++ transpiled to Java:\n" + javaCode);

                // Compile and run Java
                String output = compileAndRunJava(javaCode);
                totalCppRuns.incrementAndGet();
                callback.accept(output);
            } catch (Exception e) {
                totalFailures.incrementAndGet();
                callback.accept("C++ error: " + e.getMessage());
            }
        });
    }

    /**
     * Compile and execute Java code using the JDK's built-in compiler.
     */
    private static String compileAndRunJava(String javaSource) throws Exception {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new RuntimeException("Java compiler not available. Run with a JDK, not JRE.");
        }

        // Create temp directory for compilation
        Path tempDir = Files.createTempDirectory("mirage_cpp_");
        try {
            // Write source file
            Path sourceFile = tempDir.resolve("MirageCppProgram.java");
            Files.writeString(sourceFile, javaSource, StandardCharsets.UTF_8);

            // Compile
            List<String> compileOptions = List.of("-source", "21", "-target", "21", "-nowarn");
            javax.tools.StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);

            Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjects(sourceFile.toFile());

            javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics =
                new javax.tools.DiagnosticCollector<>();

            boolean success = compiler.getTask(null, fileManager, diagnostics,
                compileOptions, null, compilationUnits).call();

            if (!success) {
                StringBuilder errors = new StringBuilder("Compilation errors:\n");
                for (var diag : diagnostics.getDiagnostics()) {
                    errors.append("  Line ").append(diag.getLineNumber())
                          .append(": ").append(diag.getMessage(null)).append("\n");
                }
                return errors.toString();
            }

            // Run the compiled class
            String output = runProcess(new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-cp", tempDir.toString(),
                "MirageCppProgram"),
                MirageConfig.codeExecutionTimeoutSec);
            return output;
        } finally {
            // Clean up temp directory
            deleteDirectory(tempDir.toFile());
        }
    }

    // ========== Auto-Run ==========

    public static void runPythonFile(File file) {
        CompletableFuture.runAsync(() -> {
            try {
                String code = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                executePython(code, output -> {
                    totalAutoRuns.incrementAndGet();
                    LOGGER.info("[Mirage] Python auto-run output from " + file.getName() + ":\n" + output);
                });
            } catch (Exception e) {
                totalFailures.incrementAndGet();
                LOGGER.log(Level.WARNING, "[Mirage] Python auto-run error: {0}", e.getMessage());
            }
        });
    }

    public static void runCppFile(File sourceFile) {
        CompletableFuture.runAsync(() -> {
            try {
                String code = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
                executeCpp(code, output -> {
                    totalAutoRuns.incrementAndGet();
                    LOGGER.info("[Mirage] C++ auto-run output from " + sourceFile.getName() + ":\n" + output);
                });
            } catch (Exception e) {
                totalFailures.incrementAndGet();
                LOGGER.log(Level.WARNING, "[Mirage] C++ auto-run error: {0}", e.getMessage());
            }
        });
    }

    /**
     * Check for main.py and main.cpp in auto-run directories and execute them.
     */
    public static void checkAndRunAutoFiles(long currentTick) {
        if (!MirageConfig.enableAutoRun || serverRoot == null) return;

        File pythonMain = new File(new File(serverRoot, PYTHON_DIR), PYTHON_MAIN);
        if (pythonMain.exists()) {
            long lastMod = pythonMain.lastModified();
            if (lastMod != pythonMainLastModified) {
                pythonMainLastModified = lastMod;
                LOGGER.info("[Mirage] Detected " + pythonMain.getPath() + " (modified), running...");
                runPythonFile(pythonMain);
            }
        }

        File cppMain = new File(new File(serverRoot, CPP_DIR), CPP_MAIN);
        if (cppMain.exists()) {
            long lastMod = cppMain.lastModified();
            if (lastMod != cppMainLastModified) {
                cppMainLastModified = lastMod;
                LOGGER.info("[Mirage] Detected " + cppMain.getPath() + " (modified), compiling and running...");
                runCppFile(cppMain);
            }
        }
    }

    // ========== File Watcher ==========

    private static void startFileWatcher() {
        try {
            watchService = java.nio.file.FileSystems.getDefault().newWatchService();
            Path pythonPath = new File(serverRoot, PYTHON_DIR).toPath();
            Path cppPath = new File(serverRoot, CPP_DIR).toPath();

            pythonPath.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);
            cppPath.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_CREATE);

            watchThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changedFile = (Path) event.context();
                            Path dir = (Path) key.watchable();
                            Path fullPath = dir.resolve(changedFile);

                            String fileName = changedFile.toString();
                            String dirName = dir.getFileName().toString();

                            if (fileName.equals(PYTHON_MAIN) && dirName.equals(PYTHON_DIR)) {
                                Thread.sleep(500);
                                File pyFile = fullPath.toFile();
                                long lastMod = pyFile.lastModified();
                                if (lastMod != pythonMainLastModified) {
                                    pythonMainLastModified = lastMod;
                                    LOGGER.info("[Mirage] main.py modified, running...");
                                    runPythonFile(pyFile);
                                }
                            } else if (fileName.equals(CPP_MAIN) && dirName.equals(CPP_DIR)) {
                                Thread.sleep(500);
                                File cppFile = fullPath.toFile();
                                long lastMod = cppFile.lastModified();
                                if (lastMod != cppMainLastModified) {
                                    cppMainLastModified = lastMod;
                                    LOGGER.info("[Mirage] main.cpp modified, compiling and running...");
                                    runCppFile(cppFile);
                                }
                            }
                        }
                        key.reset();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {}
                }
            }, "Mirage-CodeWatcher");
            watchThread.setDaemon(true);
            watchThread.start();

            LOGGER.info("[Mirage] File watcher started for ./" + PYTHON_DIR + "/ and ./" + CPP_DIR + "/");
        } catch (Exception e) {
            LOGGER.warning("[Mirage] Failed to start file watcher: " + e.getMessage());
        }
    }

    // ========== Process Management ==========

    private static String runProcess(ProcessBuilder pb, int timeoutSec) {
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    if (output.length() > 10000) {
                        output.append("... [output truncated]");
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n[Process timed out after ").append(timeoutSec).append(" seconds]");
            }
            return output.toString().trim();
        } catch (IOException e) {
            return "Error executing: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Process interrupted.";
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    // ========== Shutdown ==========

    public static void shutdown() {
        if (watchThread != null) watchThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }

        // Close GraalVM context
        if (graalPythonContext != null) {
            try {
                Class<?> contextClass = Class.forName("org.graalvm.polyglot.Context");
                contextClass.getMethod("close").invoke(graalPythonContext);
            } catch (Exception ignored) {}
        }

        LOGGER.info("[Mirage] Code runner shut down.");
    }

    // ========== Statistics ==========

    public static Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("python_runs", totalPythonRuns.get());
        stats.put("cpp_runs", totalCppRuns.get());
        stats.put("auto_runs", totalAutoRuns.get());
        stats.put("failures", totalFailures.get());
        stats.put("graal_polyglot", graalPolyglotAvailable);
        stats.put("python_mode", graalPolyglotAvailable ? "GraalVM embedded" : "system: " + MirageConfig.pythonExecutable);
        stats.put("cpp_mode", "Java transpiler (JDK built-in compiler)");
        stats.put("auto_run_enabled", MirageConfig.enableAutoRun);
        return stats;
    }

    public static void resetStats() {
        totalPythonRuns.set(0);
        totalCppRuns.set(0);
        totalAutoRuns.set(0);
        totalFailures.set(0);
    }
}
