package dev.ktchecker.compilerworker.live;

import dev.ktchecker.workerprotocol.ApplicationWire;
import dev.ktchecker.workerprotocol.CompilerJobWire;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.List;

import static dev.ktchecker.compilerworker.live.CompilerApplicationMain.*;
import static dev.ktchecker.workerprotocol.ApplicationWire.FatalReason;

/**
 * Reviewed lift of the real adapter committed at 21fc8c7725b176d093ddf40e5d5c335b2e7c16aa,
 * source SHA-256 c56b73a527e862f02f7b6f271c0cc421877230a7bf36c7a024c8db6f8b603a87.
 * Only JDK reflection enters the pinned platform-parent compiler loader. There is no daemon,
 * CLI System.exit entry, plugin, compiler import, synthetic compiler or host-runtime dependency.
 */
final class PinnedKotlinCompilerAdapter implements CompilerApplicationMain.SessionFactory {
    private final URLClassLoader loader;
    private final Path compilerJar;
    private final ClassLoader callerLoader;
    private final GenerationFence fence;
    private final Class<?> compiler;
    private final Class<?> disposable;
    private final Class<?> collector;
    private final Class<?> pipeline;
    private final Class<?> performance;
    private final Class<?> severityType;
    private final Class<?> locationType;
    private final Method locationPath;
    private final Method locationLine;
    private final Method locationColumn;
    private final Method createArguments;
    private final Method parseArguments;
    private final Method getPerformance;
    private final Method execute;
    private final Method getExitCode;
    private final Method getCode;
    private final Method dispose;
    private final Method getApplication;
    private final Method getEnvironment;
    private final Object coreCompanion;
    private final Object services;
    private final String version;
    private final CompilerJobWire.Reset initialReset;
    private boolean retired;

    PinnedKotlinCompilerAdapter(ApplicationWire.Launch launch, CompilerJobWire.Generation generation,
                                GenerationFiles files, GenerationFence fence) throws Exception {
        CompilerJobWire.matchGeneration(launch, generation);
        this.fence = fence;
        require("false".equals(System.getProperty("kotlin.environment.keepalive")), fence, FatalReason.RESET);
        require("true".equals(System.getProperty("java.awt.headless")), fence, FatalReason.IDENTITY);
        files.verifyInstallation();
        URL[] urls = new URL[generation.compilerArtifacts().size()];
        Path selectedCompiler = null;
        for (int i = 0; i < urls.length; i++) {
            CompilerJobWire.Artifact artifact = generation.compilerArtifacts().get(i);
            Path path = files.compilerPath(i);
            urls[i] = path.toUri().toURL();
            if (artifact.fileName().equals("kotlin-compiler-embeddable-2.4.21.jar")) {
                require(selectedCompiler == null, fence, FatalReason.IDENTITY);
                selectedCompiler = path;
            }
        }
        require(selectedCompiler != null, fence, FatalReason.IDENTITY);
        compilerJar = selectedCompiler;
        loader = new URLClassLoader("ktchecker-live-" + launch.identity().generation(), urls, ClassLoader.getPlatformClassLoader());
        callerLoader = Thread.currentThread().getContextClassLoader();
        boolean applicationNull;
        boolean environmentNull;
        boolean keepAliveDisabled;
        try {
          try {
            Thread.currentThread().setContextClassLoader(loader);
            compiler = selected("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
            Class<?> cli = selected("org.jetbrains.kotlin.cli.common.CLICompiler");
            Class<?> arguments = selected("org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments");
            Class<?> serviceClass = selected("org.jetbrains.kotlin.config.Services");
            disposable = selected("org.jetbrains.kotlin.com.intellij.openapi.Disposable");
            collector = selected("org.jetbrains.kotlin.cli.common.messages.MessageCollectorWithDiagnosticId");
            Class<?> basicCollector = selected("org.jetbrains.kotlin.cli.common.messages.MessageCollector");
            pipeline = selected("org.jetbrains.kotlin.cli.pipeline.jvm.JvmCliPipeline");
            performance = selected("org.jetbrains.kotlin.util.PerformanceManager");
            severityType = selected("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity");
            locationType = selected("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation");
            locationPath = locationType.getMethod("getPath");
            locationLine = locationType.getMethod("getLine");
            locationColumn = locationType.getMethod("getColumn");
            createArguments = compiler.getMethod("createArguments");
            parseArguments = cli.getMethod("parseArguments", String[].class, arguments);
            getPerformance = cli.getMethod("getDefaultPerformanceManager");
            execute = selected("org.jetbrains.kotlin.cli.pipeline.AbstractCliPipeline").getMethod(
                    "executeAndReturnPipeLineArtifact", arguments, serviceClass, basicCollector, disposable);
            getExitCode = selected("org.jetbrains.kotlin.cli.pipeline.PipelineArtifactWithExitCode").getMethod("getExitCode");
            getCode = selected("org.jetbrains.kotlin.cli.common.ExitCode").getMethod("getCode");
            dispose = selected("org.jetbrains.kotlin.cli.common.UtilsKt").getMethod("disposeRootInWriteAction", disposable);
            getApplication = selected("org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager").getMethod("getApplication");
            Class<?> core = selected("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment");
            coreCompanion = core.getField("Companion").get(null);
            getEnvironment = selected("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment$Companion").getMethod("getApplicationEnvironment");
            services = serviceClass.getField("EMPTY").get(null);
            version = (String) call(selected("org.jetbrains.kotlin.config.KotlinCompilerVersion").getMethod("getVersion"), null);
            require(CompilerJobWire.COMPILER_VERSION.equals(version), fence, FatalReason.IDENTITY);
            // Match the reviewed CLI wrapper's cache setting without invoking its System.exit entry.
            new URLConnection(new URL("file:.")) { @Override public void connect() {} }.setDefaultUseCaches(false);
            applicationNull = call(getApplication, null) == null;
            environmentNull = call(getEnvironment, coreCompanion) == null;
            keepAliveDisabled = "false".equals(System.getProperty("kotlin.environment.keepalive"));
            require(applicationNull && environmentNull && keepAliveDisabled, fence, FatalReason.RESET);
          } finally {
            Thread.currentThread().setContextClassLoader(callerLoader);
          }
          initialReset = new CompilerJobWire.Reset(applicationNull, environmentNull, keepAliveDisabled,
                  Thread.currentThread().getContextClassLoader() == callerLoader);
          require(initialReset.complete(), fence, FatalReason.RESET);
          fence.requireHealthy();
        } catch (Throwable failure) {
            try { loader.close(); }
            catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    private Class<?> selected(String name) throws Exception {
        Class<?> type = Class.forName(name, true, loader);
        require(type.getClassLoader() == loader && type.getProtectionDomain().getCodeSource() != null
                && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(compilerJar), fence, FatalReason.IDENTITY);
        return type;
    }

    private CompilerJobWire.Reset requireReset() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        boolean applicationNull;
        boolean environmentNull;
        boolean keepAliveDisabled;
        try {
            Thread.currentThread().setContextClassLoader(loader);
            applicationNull = call(getApplication, null) == null;
            environmentNull = call(getEnvironment, coreCompanion) == null;
            keepAliveDisabled = "false".equals(System.getProperty("kotlin.environment.keepalive"));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        CompilerJobWire.Reset observed = new CompilerJobWire.Reset(applicationNull, environmentNull, keepAliveDisabled,
                previous == callerLoader && Thread.currentThread().getContextClassLoader() == callerLoader);
        require(observed.complete(), fence, FatalReason.RESET);
        return observed;
    }

    @Override public String compilerVersion() { return version; }
    @Override public boolean originsVerified() { return true; } // Only reachable after all selected(...) checks.
    @Override public CompilerJobWire.Reset initialReset() { return initialReset; }

    @Override public Session open(JobState state) throws Exception {
        state.requireExecuting();
        require(!retired, fence, FatalReason.COMPILER);
        CompilerJobWire.Reset before = requireReset();
        return new OwnedSession(state, before);
    }

    private final class OwnedSession implements Session {
        private final JobState state;
        private final CompilerJobWire.Reset before;
        private Object root;
        private boolean entered;
        private boolean closed;
        private boolean executeContextRestored;
        private boolean disposalCompleted;
        private CompilerJobWire.Reset after;

        OwnedSession(JobState state, CompilerJobWire.Reset before) {
            this.state = state;
            this.before = before;
            root = newRoot(state);
        }

        @Override public int execute(List<String> argv) throws Exception {
            state.requireExecuting();
            synchronized (this) {
                require(!entered && !closed, fence, FatalReason.COMPILER);
                entered = true;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            require(previous == callerLoader, fence, FatalReason.RESET);
            Thread.currentThread().setContextClassLoader(loader);
            try {
                // The exact real, reusable API core from the pinned committed adapter.
                // Every invocation/argument/performance manager/pipeline/collector is fresh.
                Object invocation = compiler.getConstructor().newInstance();
                Object arguments = call(createArguments, invocation);
                call(parseArguments, invocation, argv.toArray(String[]::new), arguments);
                Object manager = call(getPerformance, invocation);
                Object phased = pipeline.getConstructor(performance).newInstance(manager);
                Object messages = newCollector(state);
                Object artifact = call(execute, phased, arguments, services, messages, root);
                return (Integer) call(getCode, call(getExitCode, artifact));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                executeContextRestored = Thread.currentThread().getContextClassLoader() == callerLoader;
                if (!executeContextRestored) fence.poison(FatalReason.RESET);
            }
        }

        @Override public void close() throws Exception {
            synchronized (this) {
                require(!closed, fence, FatalReason.RESET);
                closed = true;
            }
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            if (previous != callerLoader) fence.poison(FatalReason.RESET);
            Thread.currentThread().setContextClassLoader(loader);
            try {
                // This close is owned by executeOwned's outer finally, not upstream flush paths.
                call(dispose, null, root);
                require(state.rootDisposals() == 1, fence, FatalReason.RESET);
            } finally {
                root = null;
                Thread.currentThread().setContextClassLoader(previous);
            }
            after = requireReset();
            if (!executeContextRestored) fence.poison(FatalReason.RESET);
            require(executeContextRestored && Thread.currentThread().getContextClassLoader() == callerLoader, fence, FatalReason.RESET);
            disposalCompleted = true;
        }

        @Override public CompilerJobWire.Reset beforeReset() { return before; }
        @Override public CompilerJobWire.Reset afterReset() { return after; }
        @Override public boolean disposalCompleted() { return disposalCompleted; }
    }

    private Object newRoot(JobState state) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{disposable}, (proxy, method, args) -> {
            if (identityObjectMethod(method)) return objectMethod(proxy, method, args);
            // Before method dispatch, arguments, enum names, getters, strings or a counter are read.
            try (Lease lease = state.enter(Callback.ROOT_DISPOSAL)) {
                try {
                    callbackRequire(method.getName().equals("dispose") && method.getParameterCount() == 0
                            && (args == null || args.length == 0), state);
                    state.rootDisposed(lease);
                    return null;
                } catch (Throwable failure) {
                    state.callbackFailed(FatalReason.CALLBACK);
                    throw failure;
                }
            }
        });
    }

    private Object newCollector(JobState state) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{collector}, (proxy, method, args) -> {
            if (identityObjectMethod(method)) return objectMethod(proxy, method, args);
            try (Lease lease = state.enter(Callback.COLLECTOR)) {
                try {
                    switch (method.getName()) {
                        case "clear":
                            callbackRequire(method.getParameterCount() == 0 && (args == null || args.length == 0), state);
                            state.clear(lease);
                            return null;
                        case "hasErrors":
                            callbackRequire(method.getParameterCount() == 0 && (args == null || args.length == 0), state);
                            return state.hasErrors(lease);
                        case "report":
                            callbackRequire(args != null && (args.length == 3 || args.length == 4)
                                    && method.getParameterCount() == args.length && severityType.isInstance(args[0])
                                    && args[0] instanceof Enum<?> && args[1] instanceof String, state);
                            String severity = ((Enum<?>) args[0]).name();
                            String message = (String) args[1];
                            Object location = args[2];
                            String path = "";
                            int line = -1;
                            int column = -1;
                            if (location != null) {
                                callbackRequire(locationType.isInstance(location), state);
                                path = (String) call(locationPath, location);
                                line = (Integer) call(locationLine, location);
                                column = (Integer) call(locationColumn, location);
                            }
                            String id = "";
                            if (args.length == 4 && args[3] != null) {
                                callbackRequire(args[3] instanceof String, state);
                                id = (String) args[3];
                            }
                            state.report(lease, new CompilerJobWire.Diagnostic(severity, message, path, line, column, id));
                            return null;
                        default:
                            state.callbackFailed(FatalReason.CALLBACK);
                            throw new WorkerFailure(FatalReason.CALLBACK);
                    }
                } catch (Throwable failure) {
                    // Sticky even when upstream catches a callback/proxy exception and returns zero.
                    state.callbackFailed(FatalReason.CALLBACK);
                    throw failure;
                }
            }
        });
    }

    private static void callbackRequire(boolean accepted, JobState state) {
        if (!accepted) {
            state.callbackFailed(FatalReason.CALLBACK);
            throw new WorkerFailure(FatalReason.CALLBACK);
        }
    }

    private static boolean identityObjectMethod(Method method) {
        if (method.getDeclaringClass() != Object.class) return false;
        return method.getName().equals("hashCode") || method.getName().equals("equals") || method.getName().equals("toString");
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "toString" -> "private-live-session-component";
            default -> throw new WorkerFailure(FatalReason.CALLBACK);
        };
    }

    private static Object call(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("unexpected_compiler_throwable", cause);
        }
    }

    @Override public void close() throws Exception {
        require(!retired, fence, FatalReason.COMPILER);
        retired = true;
        try { requireReset(); }
        finally { loader.close(); }
    }
}
