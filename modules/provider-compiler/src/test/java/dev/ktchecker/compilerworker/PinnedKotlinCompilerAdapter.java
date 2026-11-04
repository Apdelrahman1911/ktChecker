package dev.ktchecker.compilerworker;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ktchecker.compilerworker.CompilerWarmWorkerProtocol.*;

/** Exact reviewed external-JVM API only. No compiler imports, system-parent loader, daemon or plugins. */
public final class PinnedKotlinCompilerAdapter implements CompilerWarmWorkerMain.SessionFactory {
    private final URLClassLoader loader;
    private final Path compilerJar;
    private final Class<?> compiler;
    private final Class<?> disposable;
    private final Class<?> collector;
    private final Class<?> pipeline;
    private final Class<?> performance;
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
    private boolean retired;

    public PinnedKotlinCompilerAdapter(Plan plan, Path output) throws Exception {
        validatePlan(plan);
        require("false".equals(System.getProperty("kotlin.environment.keepalive")), "keepalive_not_disabled");
        require("true".equals(System.getProperty("java.awt.headless")), "headless_not_fixed");
        URL[] urls = new URL[plan.compilerArtifacts().size()];
        Path selectedCompiler = null;
        for (int i = 0; i < urls.length; i++) {
            Artifact artifact = plan.compilerArtifacts().get(i);
            verifyArtifact(output, "compiler/" + i + ".jar", artifact.digest(), artifact.size());
            Path path = safePath(output, "compiler/" + i + ".jar");
            urls[i] = path.toUri().toURL();
            if (artifact.fileName().equals("kotlin-compiler-embeddable-2.4.21.jar")) selectedCompiler = path;
        }
        compilerJar = java.util.Objects.requireNonNull(selectedCompiler);
        loader = new URLClassLoader("ktchecker-warm-" + plan.generation(), urls, ClassLoader.getPlatformClassLoader());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
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
            require(version.equals("2.4.21"), "compiler_version_changed");
            // Match the reviewed CLI wrapper's cache setting without invoking its System.exit entry.
            new URLConnection(new URL("file:.")) { @Override public void connect() {} }.setDefaultUseCaches(false);
            requireReset();
        } catch (Throwable failure) {
            try { loader.close(); } catch (Throwable closing) { failure.addSuppressed(closing); }
            throw failure;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private Class<?> selected(String name) throws Exception {
        Class<?> type = Class.forName(name, true, loader);
        require(type.getClassLoader() == loader && type.getProtectionDomain().getCodeSource() != null
                && Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(compilerJar), "compiler_class_origin");
        return type;
    }

    private void requireReset() throws Exception {
        require(call(getApplication, null) == null && call(getEnvironment, coreCompanion) == null
                && "false".equals(System.getProperty("kotlin.environment.keepalive")), "compiler_session_not_reset");
    }

    @Override public String compilerVersion() { return version; }

    @Override public CompilerWarmWorkerMain.Session open() throws Exception {
        require(!retired, "retired_compiler_loader");
        requireReset();
        return new OwnedSession();
    }

    private final class OwnedSession implements CompilerWarmWorkerMain.Session {
        private final AtomicInteger rootDisposals = new AtomicInteger();
        private final Object root = Proxy.newProxyInstance(loader, new Class<?>[]{disposable}, (proxy, method, args) -> {
            if (method.getName().equals("dispose")) {
                require(rootDisposals.incrementAndGet() == 1, "duplicate_root_disposal");
                return null;
            }
            return objectMethod(proxy, method, args);
        });
        private boolean entered;
        private boolean closed;
        private boolean disposalObserved;

        @Override public int execute(List<String> argv, DiagnosticSink sink) throws Exception {
            require(!entered && !closed, "reused_compiler_session");
            entered = true;
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Object invocation = compiler.getConstructor().newInstance();
                Object arguments = call(createArguments, invocation);
                call(parseArguments, invocation, argv.toArray(String[]::new), arguments);
                Object manager = call(getPerformance, invocation);
                Object phased = pipeline.getConstructor(performance).newInstance(manager);
                Object messages = Proxy.newProxyInstance(loader, new Class<?>[]{collector}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "clear": sink.clear(); return null;
                        case "hasErrors": return sink.hasErrors();
                        case "report":
                            String severity = ((Enum<?>) args[0]).name();
                            String text = (String) args[1];
                            Object location = args[2];
                            String path = "";
                            int line = -1, column = -1;
                            if (location != null) {
                                Class<?> locationType = selected("org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation");
                                path = (String) call(locationType.getMethod("getPath"), location);
                                line = (Integer) call(locationType.getMethod("getLine"), location);
                                column = (Integer) call(locationType.getMethod("getColumn"), location);
                            }
                            String id = args.length == 4 && args[3] != null ? (String) args[3] : "";
                            sink.report(new Diagnostic(severity, text, path, line, column, id));
                            return null;
                        default: return objectMethod(proxy, method, args);
                    }
                });
                // Own the disposable outside upstream setup/flush paths, including exceptions before its inner try.
                Object artifact = call(execute, phased, arguments, services, messages, root);
                return (Integer) call(getCode, call(getExitCode, artifact));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }

        @Override public void close() throws Exception {
            require(!closed, "duplicate_session_close");
            closed = true;
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                call(dispose, null, root);
                require(rootDisposals.get() == 1, "root_disposal_not_observed");
                requireReset();
                disposalObserved = true;
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }

        @Override public boolean disposalObserved() { return disposalObserved; }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "private-warm-session-component";
            default -> throw new IllegalArgumentException("unexpected_compiler_callback");
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
        require(!retired, "duplicate_loader_retirement");
        retired = true;
        loader.close();
    }
}
