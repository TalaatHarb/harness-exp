package net.talaatharb.harness;

/** Plain main class so the fat jar can start JavaFX from the classpath. */
public final class HarnessLauncher {
    private HarnessLauncher() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && "--benchmark".equals(args[0])) {
            net.talaatharb.harness.benchmark.BenchmarkMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
        } else {
            HarnessApp.main(args);
        }
    }
}
