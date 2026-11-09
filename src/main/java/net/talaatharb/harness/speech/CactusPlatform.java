package net.talaatharb.harness.speech;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the running OS/CPU to the prebuilt Cactus runtime of the pinned release in the
 * <a href="https://huggingface.co/Cactus-Compute/cactus">Cactus-Compute/cactus</a> repository
 * ({@code releases/<version>/<folder>/cactus[.exe]}), with the SHA-256 from that release's manifest.
 */
public record CactusPlatform(String folder, String executable, String sha256) {
    public static final String RELEASE = "4.1.0";
    public static final String REVISION = "v" + RELEASE;

    /** SHA-256 of each desktop runner in {@code releases/4.1.0/manifest.json}. */
    private static final Map<String, String> CHECKSUMS = Map.of(
            "windows-x86_64", "3b913456f1abd8de91aad6058ed1467eb418af7d2806f82c3d0bd8d26677ca6f",
            "windows-arm64", "06c001b2d0608cc79297db0d417f5eb83236f59105bf18e1befe7aef81ae8b84",
            "macos-arm64", "86c7bad785977c79d935a47604e14df0ef9640297b1b1280d93757a15a87f3f5",
            "linux-x86_64", "d3fabf298fed9da8db319c90fbce62c898930699d87c0468b20262860b337098",
            "linux-arm64", "088137b428c1be5cacb022a78a56f82815e5d60767002874afcc2ca7cf446074",
            "linux-armv7", "0da4b557ed60aa05273704fc1eaf87f03bb203f585a901d4d43dff49fd4684a6",
            "linux-riscv64", "1dc9cf3bdacd7196c93e0386d8a30ccbb80835a90fc935112e37e6dff49232e0",
            "linux-mipsel", "76452010a973a7f7ee68ad3aff1384feef865fafd842a9f8f5381973d0082768");

    public static CactusPlatform current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static CactusPlatform detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = normalizeArch(osArch.toLowerCase(Locale.ROOT));
        String folder = null;
        if (os.contains("win")) {
            folder = "windows-" + arch;
        } else if (os.contains("mac") || os.contains("darwin")) {
            folder = "macos-" + arch;
        } else if (os.contains("linux")) {
            folder = "linux-" + arch;
        }
        String sha256 = folder == null ? null : CHECKSUMS.get(folder);
        if (sha256 == null) {
            throw new UnsupportedOperationException(
                    "The Cactus runtime has no prebuilt binary for " + osName + " / " + osArch);
        }
        return new CactusPlatform(folder, folder.startsWith("windows-") ? "cactus.exe" : "cactus", sha256);
    }

    private static String normalizeArch(String arch) {
        switch (arch) {
            case "amd64":
            case "x86_64":
            case "x64":
                return "x86_64";
            case "aarch64":
            case "arm64":
                return "arm64";
            case "arm":
            case "armv7l":
            case "armv7":
                return "armv7";
            case "mipsel":
            case "mipsle":
                return "mipsel";
            default:
                return arch;
        }
    }

    /** Path of the runner inside the Cactus repository. */
    public String remotePath() {
        return "releases/" + RELEASE + "/" + folder + "/" + executable;
    }
}
