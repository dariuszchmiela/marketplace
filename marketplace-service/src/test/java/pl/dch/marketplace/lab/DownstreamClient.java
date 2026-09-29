package pl.dch.marketplace.lab;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Lab only. A plain <em>blocking</em> HTTP client: {@link #get} parks the calling thread until the
 * response arrives. Which thread that is (caller, pool thread, virtual thread) is decided by the caller —
 * that is the whole point of the experiments.
 */
public final class DownstreamClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final String baseUrl;
    private final Duration requestTimeout;

    public DownstreamClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
    }

    public String get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(requestTimeout).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new DownstreamException(path, "HTTP " + response.statusCode());
            }
            return response.body();
        } catch (HttpTimeoutException ex) {
            throw new DownstreamException(path, "timeout");
        } catch (IOException ex) {
            throw new DownstreamException(path, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            // Cancellation of a virtual/pool thread arrives as an interrupt: keep the flag and stop.
            Thread.currentThread().interrupt();
            throw new DownstreamException(path, "interrupted");
        }
    }

    /** A failed downstream call; the path says which of the independent calls failed. */
    public static class DownstreamException extends RuntimeException {

        private final String path;

        public DownstreamException(String path, String reason) {
            super("Downstream call " + path + " failed: " + reason);
            this.path = path;
        }

        public String path() {
            return path;
        }
    }
}
