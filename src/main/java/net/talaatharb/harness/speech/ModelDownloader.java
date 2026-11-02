package net.talaatharb.harness.speech;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Downloads model files from the Hugging Face Hub into the local models folder (no Python or CLI needed). */
public class ModelDownloader {
    public static final String ENDPOINT_PROPERTY = "harness.hf.endpoint";
    private static final Logger LOG = LoggerFactory.getLogger(ModelDownloader.class);

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30)).build();
    private final String endpoint;

    public ModelDownloader() {
        this(System.getProperty(ENDPOINT_PROPERTY, "https://huggingface.co"));
    }

    public ModelDownloader(String endpoint) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    /** A file in a Hugging Face repository and where it goes locally. */
    public record RemoteFile(String repo, String path, Path target) {
    }

    public String url(RemoteFile file) {
        StringBuilder encoded = new StringBuilder();
        for (String part : file.path().split("/")) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return endpoint + "/" + file.repo() + "/resolve/main/" + encoded;
    }

    public static boolean allPresent(List<RemoteFile> files) {
        return files.stream().allMatch(f -> Files.isRegularFile(f.target()));
    }

    /** Downloads every missing file; existing files are kept. */
    public void ensure(List<RemoteFile> files, ProgressListener progress) throws IOException {
        for (int i = 0; i < files.size(); i++) {
            RemoteFile file = files.get(i);
            if (!Files.isRegularFile(file.target())) {
                download(file, progress.slice(i / (double) files.size(), (i + 1) / (double) files.size()));
            }
        }
    }

    private void download(RemoteFile file, ProgressListener progress) throws IOException {
        Path target = file.target();
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        String url = url(file);
        LOG.info("Downloading {} to {}", url, target);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "harness-exp/1.0").GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + url, e);
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Download failed (HTTP " + response.statusCode() + "): " + url);
        }
        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        String name = file.path().substring(file.path().lastIndexOf('/') + 1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                progress.checkCancelled();
                out.write(buffer, 0, read);
                done += read;
                if (done - lastReport > (1 << 20) || done == length) {
                    lastReport = done;
                    progress.update("Downloading " + name + " (" + done / (1 << 20) + " of "
                            + (length > 0 ? length / (1 << 20) : "?") + " MB)", length > 0 ? done / (double) length : -1);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
