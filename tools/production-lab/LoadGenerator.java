import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diagnostic load generator for the marketplace HTTP API. Java only, no dependencies (JEP 330 source launcher):
 * <pre>
 * java tools/production-lab/LoadGenerator.java --base-url http://localhost:8080 --concurrency 20 --requests 2000
 * java tools/production-lab/LoadGenerator.java --scenario cart --concurrency 10 --requests 1000
 * java tools/production-lab/LoadGenerator.java --scenario checkout --concurrency 5 --requests 100 --payment-scenario SLOW
 * </pre>
 * Every worker is a "browser": it registers its own throw-away user (lab-*@example.com) through the real API, keeps
 * the session cookie, fetches the CSRF token and sends it as X-XSRF-TOKEN on state-changing requests — the same
 * path as the React app. Scenarios:
 * <ul>
 *   <li>{@code browse} (default): GET /api/products, GET /api/products/{id}, GET /api/cart — read-only;</li>
 *   <li>{@code cart}: browse + add / change / remove cart items (DB writes, row locks), no checkout;</li>
 *   <li>{@code checkout}: add an item + POST /api/checkout (hits payment-service; with --payment-scenario, e.g. SLOW or
 *       DECLINED, which marketplace forwards only when PAYMENT_FORWARD_SCENARIO_HEADER=true; with
 *       --payment-scenario-every 20 only every 20th checkout uses it, i.e. 5% slow payments).</li>
 * </ul>
 * It reports requests, successes, failures, throughput and min/p50/p95/p99/max per endpoint and overall.
 * <p>
 * <b>Not a benchmark.</b> It is a closed-loop generator (each worker waits for its response before sending the next
 * request), so when the server slows down it also sends less — "coordinated omission": real users would keep arriving,
 * and the measured tail understates what they would see. Use it to reproduce and observe behaviour (p99 vs p50, pool
 * pending, GC, threads) on your machine, not to publish numbers.
 */
public class LoadGenerator {

    private static final Pattern PRODUCT_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final String PASSWORD = "load-generator-password";

    record Sample(String endpoint, int status, long nanos, String outcome) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String baseUrl = options.getOrDefault("base-url", "http://localhost:8080");
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "10"));
        int requests = Integer.parseInt(options.getOrDefault("requests", "1000"));
        String scenario = options.getOrDefault("scenario", "browse");
        String paymentScenario = options.get("payment-scenario");
        int scenarioEvery = Integer.parseInt(options.getOrDefault("payment-scenario-every", "1"));
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "0"));

        System.out.printf("LoadGenerator: base-url=%s scenario=%s concurrency=%d requests=%d warmup=%d%s%n", baseUrl, scenario,
                concurrency, requests, warmup, paymentScenario == null ? "" : " payment-scenario=" + paymentScenario
                + (scenarioEvery > 1 ? " (every " + scenarioEvery + ". checkout)" : ""));
        List<Long> productIds = productIds(baseUrl);
        System.out.printf("products: %s; creating %d users...%n", productIds, concurrency);

        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            browsers.add(Browser.signUp(baseUrl));
        }
        if (warmup > 0) {
            run(browsers, warmup, scenario, productIds, paymentScenario, scenarioEvery);
            System.out.printf("warm-up: %d requests done (not reported)%n", warmup);
        }
        long start = System.nanoTime();
        List<Sample> samples = run(browsers, requests, scenario, productIds, paymentScenario, scenarioEvery);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        report(samples, elapsed);
    }

    private static List<Sample> run(List<Browser> browsers, int requests, String scenario, List<Long> productIds,
                                    String paymentScenario, int scenarioEvery) throws Exception {
        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        AtomicInteger remaining = new AtomicInteger(requests);
        AtomicInteger checkouts = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workers = new ArrayList<>();
            for (Browser browser : browsers) {
                workers.add(executor.submit(() -> {
                    while (remaining.getAndDecrement() > 0) {
                        long productId = productIds.get(ThreadLocalRandom.current().nextInt(productIds.size()));
                        samples.add(switch (scenario) {
                            case "browse" -> browseStep(browser, productId);
                            case "cart" -> cartStep(browser, productId);
                            case "checkout" -> checkoutStep(browser, productId,
                                    checkouts.incrementAndGet() % scenarioEvery == 0 ? paymentScenario : null);
                            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
                        });
                    }
                    return null;
                }));
            }
            for (Future<?> worker : workers) {
                worker.get();
            }
        }
        return new ArrayList<>(samples);
    }

    private static Sample browseStep(Browser browser, long productId) {
        return switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0 -> browser.call("GET /api/products", "GET", "/api/products", null, Map.of());
            case 1 -> browser.call("GET /api/products/{id}", "GET", "/api/products/" + productId, null, Map.of());
            default -> browser.call("GET /api/cart", "GET", "/api/cart", null, Map.of());
        };
    }

    private static Sample cartStep(Browser browser, long productId) {
        return switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> browser.call("GET /api/cart", "GET", "/api/cart", null, Map.of());
            case 1 -> browser.call("POST /api/cart/items", "POST", "/api/cart/items",
                    "{\"productId\": " + productId + ", \"quantity\": 1}", Map.of());
            case 2 -> browser.call("PUT /api/cart/items/{id}", "PUT", "/api/cart/items/" + productId,
                    "{\"quantity\": 2}", Map.of());
            default -> browser.call("DELETE /api/cart/items/{id}", "DELETE", "/api/cart/items/" + productId, null, Map.of());
        };
    }

    private static Sample checkoutStep(Browser browser, long productId, String paymentScenario) {
        browser.call("POST /api/cart/items", "POST", "/api/cart/items", "{\"productId\": " + productId + ", \"quantity\": 1}",
                Map.of());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Idempotency-Key", UUID.randomUUID().toString());
        if (paymentScenario != null) {
            headers.put("X-Payment-Scenario", paymentScenario);
        }
        return browser.call("POST /api/checkout", "POST", "/api/checkout", null, headers);
    }

    /** One simulated browser: own cookie jar (session + CSRF cookie), own logged-in user. */
    static final class Browser {

        private final String baseUrl;
        private final CookieManager cookies = new CookieManager();
        private final HttpClient client;

        private Browser(String baseUrl) {
            this.baseUrl = baseUrl;
            this.client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        }

        static Browser signUp(String baseUrl) {
            Browser browser = new Browser(baseUrl);
            String email = "lab-" + UUID.randomUUID() + "@example.com";
            Sample registered = browser.call("register", "POST", "/api/auth/register",
                    "{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}", Map.of());
            if (registered.status() != 201) {
                throw new IllegalStateException("registration failed: HTTP " + registered.status());
            }
            return browser;
        }

        Sample call(String endpoint, String method, String path, String json, Map<String, String> headers) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            headers.forEach(request::header);
            if (!method.equals("GET")) {
                request.header("X-XSRF-TOKEN", csrfToken());
            }
            if (json != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
            long start = System.nanoTime();
            try {
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                long took = System.nanoTime() - start;
                String outcome = null;
                if (endpoint.equals("POST /api/checkout")) {
                    Matcher status = STATUS.matcher(response.body());
                    outcome = status.find() ? status.group(1) : "HTTP " + response.statusCode();
                }
                return new Sample(endpoint, response.statusCode(), took, outcome);
            } catch (Exception ex) {
                return new Sample(endpoint, -1, System.nanoTime() - start, ex.getClass().getSimpleName());
            }
        }

        /** The XSRF-TOKEN cookie; fetched from /api/auth/csrf when missing (e.g. right after login/registration). */
        private String csrfToken() {
            String token = cookie("XSRF-TOKEN");
            if (token == null || token.isEmpty()) {
                try {
                    client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/csrf")).build(),
                            HttpResponse.BodyHandlers.discarding());
                } catch (Exception ex) {
                    throw new IllegalStateException("could not fetch a CSRF token", ex);
                }
                token = cookie("XSRF-TOKEN");
            }
            return token == null ? "" : token;
        }

        private String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals(name))
                    .map(HttpCookie::getValue)
                    .findFirst().orElse(null);
        }
    }

    private static List<Long> productIds(String baseUrl) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/products")).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET /api/products: HTTP " + response.statusCode());
        }
        List<Long> ids = new ArrayList<>();
        Matcher matcher = PRODUCT_ID.matcher(response.body());
        while (matcher.find()) {
            ids.add(Long.parseLong(matcher.group(1)));
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("no products");
        }
        return ids;
    }

    private static void report(List<Sample> samples, Duration elapsed) {
        Map<String, List<Sample>> byEndpoint = new TreeMap<>();
        for (Sample sample : samples) {
            byEndpoint.computeIfAbsent(sample.endpoint(), key -> new ArrayList<>()).add(sample);
        }
        System.out.printf(Locale.ROOT, "%n%-28s %8s %9s %8s %9s %8s %8s %8s %8s %8s %8s%n", "endpoint", "requests", "successes",
                "failures", "req/s", "min ms", "mean ms", "p50 ms", "p95 ms", "p99 ms", "max ms");
        byEndpoint.forEach((endpoint, list) -> line(endpoint, list, elapsed));
        line("ALL", samples, elapsed);

        Map<String, Integer> statuses = new TreeMap<>();
        Map<String, Integer> outcomes = new TreeMap<>();
        for (Sample sample : samples) {
            statuses.merge(sample.status() < 0 ? "error" : Integer.toString(sample.status()), 1, Integer::sum);
            if (sample.outcome() != null) {
                outcomes.merge(sample.outcome(), 1, Integer::sum);
            }
        }
        System.out.printf("%nHTTP status: %s%n", statuses);
        if (!outcomes.isEmpty()) {
            System.out.printf("checkout outcomes: %s%n", outcomes);
        }
        System.out.printf(Locale.ROOT, "elapsed %.1f s - diagnostic load, not a benchmark (closed loop, coordinated omission)%n",
                elapsed.toMillis() / 1000.0);
    }

    private static void line(String name, List<Sample> list, Duration elapsed) {
        long[] nanos = list.stream().mapToLong(Sample::nanos).sorted().toArray();
        long failures = list.stream().filter(sample -> sample.status() < 0 || sample.status() >= 500
                || (sample.status() >= 400 && sample.status() != 404 && sample.status() != 409)).count();
        System.out.printf(Locale.ROOT, "%-28s %8d %9d %8d %9.1f %8.1f %8.1f %8.1f %8.1f %8.1f %8.1f%n", name, list.size(),
                list.size() - failures, failures, list.size() / (elapsed.toNanos() / 1e9), ms(nanos[0]),
                ms((long) Arrays.stream(nanos).average().orElse(0)),
                ms(percentile(nanos, 50)), ms(percentile(nanos, 95)), ms(percentile(nanos, 99)), ms(nanos[nanos.length - 1]));
    }

    private static long percentile(long[] sorted, double percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("usage: --base-url URL --scenario browse|cart|checkout --concurrency N "
                        + "--requests N [--warmup N] [--payment-scenario SLOW|DECLINED|...] [--payment-scenario-every N]; got " + Arrays.toString(args));
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }
}
