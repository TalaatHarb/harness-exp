package net.talaatharb.harness;

/** Plain main class so the fat jar can start JavaFX from the classpath. */
public final class HarnessLauncher {
    private HarnessLauncher() {
    }

    public static void main(String[] args) {
        HarnessApp.main(args);
    }
}
