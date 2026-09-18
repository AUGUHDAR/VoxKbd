package com.voxkbd.mod.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Desktop Java-runtime detection + cache (decision D1 / D12 / §3.1 / §4.4).
 *
 * <p>Probe order (must be: <b>cache &#8594; PATH &#8594; MC current JVM (java.home / process cmdline)
 * &#8594; null</b>):</p>
 * <ol>
 *   <li><b>cache</b> &#8212; a previously cached absolute java path (e.g. {@code Config.javaPath}).</li>
 *   <li><b>PATH</b> &#8212; {@code java} / {@code java.exe} found on the system {@code PATH}.</li>
 *   <li><b>MC current JVM</b> &#8212; {@code java.home/bin/java} then the executable of the running
 *       process ({@code ProcessHandle.current()}).</li>
 *   <li><b>null</b> &#8212; caller falls back to in-process GLFW mode (no crash).</li>
 * </ol>
 *
 * <p>On Android (FCL / ZL2) the mod runs inside the launcher JVM and there is no standalone java
 * executable, so {@link #detect(String)} is a no-op returning {@code null} and no fallback is needed.</p>
 *
 * <p>This class is loader-agnostic (only JDK); it is part of the javac-verifiable surface of voxkbd-mod.</p>
 */
public final class JavaDetector {

    private JavaDetector() {}

    /** GLFW-independent stable GLFW modifier bits used only for sanity; not user-facing. */
    private static final String[] EXE_NAMES = {"java", "java.exe"};

    /** Returns true when running on an Android JVM (Dalvik/ART). */
    public static boolean isAndroid() {
        String vmName = System.getProperty("java.vm.name", "");
        String vmVendor = System.getProperty("java.vm.vendor", "");
        String rtName = System.getProperty("java.runtime.name", "");
        return vmName.contains("Dalvik") || vmName.contains("Android")
                || vmVendor.contains("Android") || rtName.contains("Android");
    }

    /**
     * Detect an absolute java executable path following the documented order.
     *
     * @param cachedJavaPath a previously cached path (may be null); checked first.
     * @return absolute java path, or {@code null} if none could be found/executed.
     */
    public static String detect(String cachedJavaPath) {
        if (isAndroid()) {
            return null; // no standalone java on Android; in-process backend only
        }
        String found = fromCache(cachedJavaPath);
        if (found != null) return found;
        found = fromPath();
        if (found != null) return found;
        found = fromJavaHome();
        if (found != null) return found;
        found = fromCurrentProcess();
        if (found != null) return found;
        return null;
    }

    private static String fromCache(String cached) {
        if (cached == null || cached.isBlank()) return null;
        Path p = Paths.get(cached);
        return canExecuteJava(p) ? p.toAbsolutePath().toString() : null;
    }

    private static String fromPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) continue;
            for (String name : EXE_NAMES) {
                Path p = Paths.get(dir, name);
                if (canExecuteJava(p)) return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String fromJavaHome() {
        String home = System.getProperty("java.home");
        if (home == null) return null;
        String name = isWindows() ? "java.exe" : "java";
        Path p = Paths.get(home, "bin", name);
        return canExecuteJava(p) ? p.toAbsolutePath().toString() : null;
    }

    private static String fromCurrentProcess() {
        try {
            return ProcessHandle.current().info().command()
                    .filter(cmd -> canExecuteJava(Paths.get(cmd)))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Best-effort check that a candidate really is an executable java: spawn {@code <java> -version}
     * and require exit code 0. Cheap enough to run only at startup; guarded by a 5s timeout.
     */
    private static boolean canExecuteJava(Path p) {
        if (p == null || !Files.isRegularFile(p) || !Files.isReadable(p)) return false;
        List<String> cmd = new ArrayList<>();
        cmd.add(p.toString());
        cmd.add("-version");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        try {
            Process proc = pb.start();
            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
