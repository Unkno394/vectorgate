import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.sun.net.httpserver.HttpServer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class main {
    private static final String[] FEATURE_ORDER = {"requests_per_second", "mean_inter_request_ms", "error_rate", "mean_request_bytes", "mean_processing_ms", "unique_endpoints", "endpoint_repeatability", "get_share", "post_share", "other_methods_share"};
    private static final int MINIMUM_BASELINE_WINDOWS = 30;
    private static final Pattern STRING = Pattern.compile("\\\"([^\\\"]+)\\\":\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private static final Pattern NUMBER = Pattern.compile("\\\"([^\\\"]+)\\\":(-?\\d+(?:\\.\\d+)?)");

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        long windowMillis = (args.length > 1 ? Long.parseLong(args[1]) : 60) * 1000L;
        ClickHouseSink clickHouse = ClickHouseSink.fromEnvironment();
        GatewayControlClient gatewayControl = GatewayControlClient.fromEnvironment();
        NdjsonSink rawEventLog = NdjsonSink.fromEnvironment("RAW_EVENT_LOG_PATH");
        NdjsonSink vectorLog = NdjsonSink.fromEnvironment("VECTOR_LOG_PATH");
        BaselineLoader baselineLoader = BaselineLoader.fromEnvironment();
        LiveStateApi liveState = LiveStateApi.fromEnvironment();
        System.out.println("Analytics receiver listening on UDP " + port + "; aggregation window=" + windowMillis / 1000 + "s");
        Map<String, Window> windows = new HashMap<>();
        Map<String, FeatureProfile> profiles = new HashMap<>();
        Instant windowStart = Instant.now();
        long deadline = System.currentTimeMillis() + windowMillis;
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(1000);
            byte[] data = new byte[65535];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    socket.receive(packet);
                    String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    Event event = Event.parse(json);
                    if (event != null) {
                        windows.computeIfAbsent(event.baselineScope + "\u0000" + event.backend, ignored -> new Window(event.baselineScope, event.backend)).add(event);
                        rawEventLog.append(json);
                        System.out.println("TRAFFIC_EVENT " + json);
                    }
                } catch (java.net.SocketTimeoutException ignored) { }
                if (System.currentTimeMillis() >= deadline) {
                    Instant windowEnd = Instant.now();
                    for (Map.Entry<String, Window> entry : windows.entrySet()) {
                        double[] rawVector = entry.getValue().toVector(windowMillis);
                        FeatureProfile profile = profiles.computeIfAbsent(entry.getKey(), ignored -> baselineLoader.profileFor(entry.getKey(), FEATURE_ORDER.length));
                        StandardizedVector standardized = profile.standardize(rawVector);
                        BaselineModel baseline = profile.model();
                        double score = AnomalyScorer.score(rawVector, baseline);
                        String level = AnomalyScorer.level(score, baseline.samples);
                        if ("normal".equals(level)) profile.update(rawVector);
                        String vector = entry.getValue().toJson(windowStart, windowEnd, rawVector, standardized, baseline, score, level, profile.source);
                        System.out.println("STATE_VECTOR " + vector);
                        vectorLog.append(vector);
                        clickHouse.insert(vector);
                        liveState.publish(vector, score, level);
                        gatewayControl.sendIfRequired(entry.getValue(), standardized, score, level, windowMillis);
                    }
                    windows = new HashMap<>();
                    windowStart = windowEnd;
                    deadline = System.currentTimeMillis() + windowMillis;
                }
            }
        }
    }

    static final class Event {
        final String method, endpoint, backend, clientIp, baselineScope; final Instant timestamp; final long requestBytes, durationMs; final int status;
        Event(String method, String endpoint, String backend, String clientIp, String baselineScope, Instant timestamp, long requestBytes, int status, long durationMs) { this.method = method; this.endpoint = endpoint; this.backend = backend; this.clientIp = clientIp; this.baselineScope = baselineScope; this.timestamp = timestamp; this.requestBytes = requestBytes; this.status = status; this.durationMs = durationMs; }
        static Event parse(String json) {
            Map<String, String> strings = new HashMap<>(); Map<String, Double> numbers = new HashMap<>();
            Matcher s = STRING.matcher(json); while (s.find()) strings.put(s.group(1), s.group(2));
            Matcher n = NUMBER.matcher(json); while (n.find()) numbers.put(n.group(1), Double.parseDouble(n.group(2)));
            if (!strings.containsKey("timestamp") || !strings.containsKey("method") || !strings.containsKey("endpoint") || !numbers.containsKey("request_bytes")) return null;
            String backend = strings.getOrDefault("backend_host", "unknown") + ":" + strings.getOrDefault("backend_port", "");
            String baselineScope = strings.getOrDefault("baseline_scope", "gateway").replaceAll("[^a-zA-Z0-9_.-]", "_");
            if (baselineScope.isBlank()) baselineScope = "gateway";
            try {
                return new Event(strings.get("method"), strings.get("endpoint"), backend, strings.getOrDefault("client_ip", ""), baselineScope, Instant.parse(strings.get("timestamp")), numbers.get("request_bytes").longValue(), numbers.getOrDefault("response_status", 0d).intValue(), numbers.getOrDefault("duration_ms", 0d).longValue());
            } catch (java.time.format.DateTimeParseException ignored) { return null; }
        }
    }

    static final class Window {
        final String baselineScope, backend; long requests, bytes, duration; long errors; final Set<String> endpoints = new HashSet<>(); final Map<String, Long> methods = new HashMap<>(); final Map<String, Long> clientRequests = new HashMap<>(); final ArrayList<Instant> timestamps = new ArrayList<>();
        Window(String baselineScope, String backend) { this.baselineScope = baselineScope; this.backend = backend; }
        void add(Event event) { requests++; bytes += event.requestBytes; duration += event.durationMs; if (event.status >= 400) errors++; endpoints.add(event.endpoint); methods.merge(event.method, 1L, Long::sum); if (!event.clientIp.isBlank()) clientRequests.merge(event.clientIp, 1L, Long::sum); timestamps.add(event.timestamp); }
        double[] toVector(long windowMillis) {
            double seconds = windowMillis / 1000.0; double rps = requests / seconds;
            double avgSize = requests == 0 ? 0 : (double) bytes / requests;
            double avgDuration = requests == 0 ? 0 : (double) duration / requests;
            double errorRate = requests == 0 ? 0 : (double) errors / requests;
            double repeatability = requests == 0 ? 0 : 1.0 - (double) endpoints.size() / requests;
            double getShare = requests == 0 ? 0 : methods.getOrDefault("GET", 0L) / (double) requests;
            double postShare = requests == 0 ? 0 : methods.getOrDefault("POST", 0L) / (double) requests;
            return new double[] {rps, meanInterRequestMs(), errorRate, avgSize, avgDuration, endpoints.size(), repeatability, getShare, postShare, 1.0 - getShare - postShare};
        }
        String toJson(Instant windowStart, Instant windowEnd, double[] rawVector, StandardizedVector standardized, BaselineModel baseline, double score, String level, String baselineSource) {
            double rps = rawVector[0], interval = rawVector[1], errorRate = rawVector[2], avgSize = rawVector[3], avgDuration = rawVector[4], repeatability = rawVector[6];
            return String.format(java.util.Locale.ROOT,
                "{\"window_start\":\"%s\",\"window_end\":\"%s\",\"baseline_scope\":\"%s\",\"backend\":\"%s\",\"requests\":%d,\"requests_per_second\":%.4f,\"mean_inter_request_ms\":%.4f,\"mean_request_bytes\":%.4f,\"mean_processing_ms\":%.4f,\"error_rate\":%.6f,\"unique_endpoints\":%d,\"endpoint_repeatability\":%.6f,\"method_counts\":\"%s\",\"feature_vector\":%s,\"standardized_vector\":%s,\"standardization_samples\":%d,\"standardization_ready\":%d,\"baseline_mean\":%s,\"baseline_stddev\":%s,\"covariance_matrix\":%s,\"baseline_samples\":%d,\"anomaly_score\":%.6f,\"anomaly_level\":\"%s\",\"baseline_source\":\"%s\"}",
                windowStart, windowEnd, baselineScope.replace("\"", ""), backend.replace("\"", ""), requests, rps, interval, avgSize, avgDuration, errorRate, endpoints.size(), repeatability, mapJson().replace("\"", "\\\""), vectorJson(rawVector), vectorJson(standardized.values), standardized.samples, standardized.ready ? 1 : 0, vectorJson(baseline.mean), vectorJson(baseline.stddev), matrixJson(baseline.covariance), baseline.samples, score, level, baselineSource);
        }
        double meanInterRequestMs() {
            if (timestamps.size() < 2) return 0;
            Collections.sort(timestamps);
            long totalMillis = 0;
            for (int index = 1; index < timestamps.size(); index++) totalMillis += Duration.between(timestamps.get(index - 1), timestamps.get(index)).toMillis();
            return totalMillis / (double) (timestamps.size() - 1);
        }
        String mapJson() { StringBuilder out = new StringBuilder("{"); boolean first = true; for (Map.Entry<String, Long> e : methods.entrySet()) { if (!first) out.append(','); out.append('\"').append(e.getKey().replace("\"", "")).append("\":").append(e.getValue()); first = false; } return out.append('}').toString(); }
        String vectorJson(double[] values) { StringBuilder out = new StringBuilder("["); for (int index = 0; index < values.length; index++) { if (index > 0) out.append(','); out.append(String.format(java.util.Locale.ROOT, "%.8f", values[index])); } return out.append(']').toString(); }
        String matrixJson(double[][] values) { StringBuilder out = new StringBuilder("["); for (int row = 0; row < values.length; row++) { if (row > 0) out.append(','); out.append(vectorJson(values[row])); } return out.append(']').toString(); }
        Map.Entry<String, Long> dominantClient() { return clientRequests.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null); }
    }
    static final class FeatureProfile {
        long count; final double[] mean, m2; final double[][] covarianceM2; final String source;
        FeatureProfile(int dimensions) { this(dimensions, "online"); }
        FeatureProfile(int dimensions, String source) { mean = new double[dimensions]; m2 = new double[dimensions]; covarianceM2 = new double[dimensions][dimensions]; this.source = source; }
        static FeatureProfile fromBaseline(long samples, double[] sourceMean, double[] sourceStddev, double[][] sourceCovariance) {
            FeatureProfile profile = new FeatureProfile(sourceMean.length, "initial-v1"); profile.count = samples;
            for (int row = 0; row < sourceMean.length; row++) { profile.mean[row] = sourceMean[row]; profile.m2[row] = sourceStddev[row] * sourceStddev[row] * Math.max(0, samples - 1); for (int column = 0; column < sourceMean.length; column++) profile.covarianceM2[row][column] = sourceCovariance[row][column] * Math.max(0, samples - 1); }
            return profile;
        }
        StandardizedVector standardize(double[] values) {
            double[] normalized = new double[values.length]; boolean ready = count >= MINIMUM_BASELINE_WINDOWS;
            if (ready) for (int index = 0; index < values.length; index++) { double variance = m2[index] / (count - 1); normalized[index] = variance > 0 ? (values[index] - mean[index]) / Math.sqrt(variance) : 0; }
            return new StandardizedVector(normalized, count, ready);
        }
        void update(double[] values) {
            count++;
            double[] delta = new double[values.length];
            double[] delta2 = new double[values.length];
            for (int index = 0; index < values.length; index++) {
                delta[index] = values[index] - mean[index];
                mean[index] += delta[index] / count;
                delta2[index] = values[index] - mean[index];
                m2[index] += delta[index] * delta2[index];
            }
            for (int row = 0; row < values.length; row++)
                for (int column = 0; column < values.length; column++)
                    covarianceM2[row][column] += delta[row] * delta2[column];
        }
        BaselineModel model() {
            double[] stddev = new double[mean.length];
            double[][] covariance = new double[mean.length][mean.length];
            if (count > 1) for (int row = 0; row < mean.length; row++) {
                stddev[row] = Math.sqrt(Math.max(0, m2[row] / (count - 1)));
                for (int column = 0; column < mean.length; column++) covariance[row][column] = covarianceM2[row][column] / (count - 1);
            }
            return new BaselineModel(count, mean.clone(), stddev, covariance);
        }
    }
    static final class StandardizedVector { final double[] values; final long samples; final boolean ready; StandardizedVector(double[] values, long samples, boolean ready) { this.values = values; this.samples = samples; this.ready = ready; } }
    static final class BaselineModel { final long samples; final double[] mean, stddev; final double[][] covariance; BaselineModel(long samples, double[] mean, double[] stddev, double[][] covariance) { this.samples = samples; this.mean = mean; this.stddev = stddev; this.covariance = covariance; } }
    static final class BaselineLoader {
        private final FeatureProfile profile; private final String profileKey;
        static BaselineLoader fromEnvironment() {
            String path = System.getenv("BASELINE_PATH");
            if (path == null || path.isBlank()) { System.out.println("Initial baseline disabled: set BASELINE_PATH to load one"); return new BaselineLoader(null, ""); }
            try {
                String json = Files.readString(Path.of(path)); long samples = Long.parseLong(number(json, "samples"));
                double[] mean = numbers(array(json, "mean")); double[] stddev = numbers(array(json, "stddev")); double[] covarianceValues = numbers(array(json, "covariance_matrix"));
                if (mean.length != FEATURE_ORDER.length || stddev.length != FEATURE_ORDER.length || covarianceValues.length != FEATURE_ORDER.length * FEATURE_ORDER.length) throw new IllegalArgumentException("baseline dimensions differ from feature order");
                double[][] covariance = new double[FEATURE_ORDER.length][FEATURE_ORDER.length]; for (int row = 0; row < FEATURE_ORDER.length; row++) System.arraycopy(covarianceValues, row * FEATURE_ORDER.length, covariance[row], 0, FEATURE_ORDER.length);
                String key = text(json, "baseline_scope") + "\u0000" + text(json, "backend");
                System.out.println("Loaded initial baseline: " + samples + " windows from " + path); return new BaselineLoader(FeatureProfile.fromBaseline(samples, mean, stddev, covariance), key);
            } catch (Exception error) { System.err.println("Cannot load initial baseline: " + error.getMessage()); return new BaselineLoader(null, ""); }
        }
        BaselineLoader(FeatureProfile profile, String profileKey) { this.profile = profile; this.profileKey = profileKey; }
        FeatureProfile profileFor(String requestedKey, int dimensions) { return profile == null || !profileKey.equals(requestedKey) ? new FeatureProfile(dimensions) : FeatureProfile.fromBaseline(profile.count, profile.mean, profile.model().stddev, profile.model().covariance); }
        static String number(String json, String key) { Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json); if (!matcher.find()) throw new IllegalArgumentException("missing " + key); return matcher.group(1); }
        static String text(String json, String key) { Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(json); if (!matcher.find()) throw new IllegalArgumentException("missing " + key); return matcher.group(1); }
        static String array(String json, String key) { int keyAt = json.indexOf("\"" + key + "\""); if (keyAt < 0) throw new IllegalArgumentException("missing " + key); int start = json.indexOf('[', keyAt); int depth = 0; for (int index = start; index < json.length(); index++) { char value = json.charAt(index); if (value == '[') depth++; if (value == ']' && --depth == 0) return json.substring(start, index + 1); } throw new IllegalArgumentException("unclosed " + key); }
        static double[] numbers(String json) { Matcher matcher = Pattern.compile("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?").matcher(json); ArrayList<Double> out = new ArrayList<>(); while (matcher.find()) out.add(Double.parseDouble(matcher.group())); double[] values = new double[out.size()]; for (int index = 0; index < values.length; index++) values[index] = out.get(index); return values; }
    }
    static final class AnomalyScorer {
        static double score(double[] values, BaselineModel baseline) {
            if (baseline.samples < MINIMUM_BASELINE_WINDOWS) return 0;
            int size = values.length; double[][] matrix = new double[size][size]; double[] delta = new double[size];
            for (int row = 0; row < size; row++) { delta[row] = values[row] - baseline.mean[row]; for (int column = 0; column < size; column++) matrix[row][column] = baseline.covariance[row][column]; matrix[row][row] += Math.max(1e-9, Math.abs(matrix[row][row]) * 1e-6); }
            double[][] inverse = invert(matrix); double total = 0; for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) total += delta[row] * inverse[row][column] * delta[column]; return Math.sqrt(Math.max(0, total));
        }
        static String level(double score, long samples) { if (samples < MINIMUM_BASELINE_WINDOWS) return "warming_up"; if (score >= 6.0) return "critical"; if (score >= 4.0) return "warning"; return "normal"; }
        static double[][] invert(double[][] source) {
            int size = source.length; double[][] work = new double[size][size * 2];
            for (int row = 0; row < size; row++) { System.arraycopy(source[row], 0, work[row], 0, size); work[row][row + size] = 1; }
            for (int column = 0; column < size; column++) { int pivot = column; for (int row = column + 1; row < size; row++) if (Math.abs(work[row][column]) > Math.abs(work[pivot][column])) pivot = row; double[] swap = work[column]; work[column] = work[pivot]; work[pivot] = swap; if (Math.abs(work[column][column]) < 1e-12) throw new IllegalArgumentException("covariance matrix is singular"); double divisor = work[column][column]; for (int index = 0; index < size * 2; index++) work[column][index] /= divisor; for (int row = 0; row < size; row++) if (row != column) { double factor = work[row][column]; for (int index = 0; index < size * 2; index++) work[row][index] -= factor * work[column][index]; } }
            double[][] inverse = new double[size][size]; for (int row = 0; row < size; row++) System.arraycopy(work[row], size, inverse[row], 0, size); return inverse;
        }
    }
    static final class LiveStateApi {
        private final Map<String, String> latest = new ConcurrentHashMap<>(); private final ArrayList<String> incidents = new ArrayList<>();
        static LiveStateApi fromEnvironment() { int port = Integer.parseInt(System.getenv().getOrDefault("ANALYTICS_API_PORT", "9091")); LiveStateApi api = new LiveStateApi(); try { HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0); server.createContext("/api/state", exchange -> { String body = api.json(); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length); exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8)); exchange.close(); }); server.start(); System.out.println("Analytics live API listening on http://127.0.0.1:" + port + "/api/state"); } catch (Exception error) { System.err.println("Analytics live API disabled: " + error.getMessage()); } return api; }
        synchronized void publish(String vector, double score, String level) { String backend = vector.contains("\"backend\":\"") ? vector.split("\\\"backend\\\":\\\"")[1].split("\\\"")[0] : "unknown"; latest.put(backend, vector); if ("warning".equals(level) || "critical".equals(level)) { incidents.add(0, vector); if (incidents.size() > 50) incidents.remove(incidents.size() - 1); } }
        synchronized String json() { return "{\"windows\":[" + String.join(",", latest.values()) + "],\"incidents\":[" + String.join(",", incidents) + "]}"; }
    }
    static final class NdjsonSink {
        private final Path path;
        static NdjsonSink fromEnvironment(String variable) {
            String value = System.getenv(variable);
            if (value == null || value.isBlank()) return new NdjsonSink(null);
            try {
                Path path = Path.of(value);
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                return new NdjsonSink(path);
            } catch (Exception error) { System.err.println("Cannot initialize " + variable + ": " + error.getMessage()); return new NdjsonSink(null); }
        }
        NdjsonSink(Path path) { this.path = path; }
        synchronized void append(String json) {
            if (path == null) return;
            try { Files.writeString(path, json + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (Exception error) { System.err.println("NDJSON log write failed: " + error.getMessage()); }
        }
    }
    static final class GatewayControlClient {
        private static final double RPS_ANOMALY_Z = 3.0;
        private static final double DOMINANT_CLIENT_SHARE = 0.70;
        private final String url, token; private final HttpClient client = HttpClient.newHttpClient();
        static GatewayControlClient fromEnvironment() {
            String url = System.getenv("GATEWAY_CONTROL_URL");
            if (url == null || url.isBlank()) { System.out.println("Gateway control disabled: set GATEWAY_CONTROL_URL to enable Java -> C++ decisions"); return new GatewayControlClient(null, null); }
            return new GatewayControlClient(url.replaceAll("/+$", "") + "/__gateway/decisions", System.getenv("GATEWAY_CONTROL_TOKEN"));
        }
        GatewayControlClient(String url, String token) { this.url = url; this.token = token; }
        void sendIfRequired(Window window, StandardizedVector vector, double score, String level, long windowMillis) {
            if (url == null || !"critical".equals(level) || vector.values[0] < RPS_ANOMALY_Z) return;
            Map.Entry<String, Long> dominant = window.dominantClient();
            if (dominant == null || dominant.getValue() < window.requests * DOMINANT_CLIENT_SHARE) return;
            long observedRps = Math.max(1, Math.round(dominant.getValue() / (windowMillis / 1000.0)));
            long limit = Math.max(1, observedRps / 2);
            String body = String.format(java.util.Locale.ROOT,
                "{\"action\":\"rate_limit\",\"client_ip\":\"%s\",\"requests_per_second\":%d,\"ttl_seconds\":300,\"reason\":\"critical joint anomaly: score=%.2f; rps_z=%.2f\"}",
                dominant.getKey().replace("\\", "\\\\").replace("\"", "\\\""), limit, score, vector.values[0]);
            try {
                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString(body));
                if (token != null && !token.isBlank()) requestBuilder.header("X-Gateway-Control-Token", token);
                HttpRequest request = requestBuilder.build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) System.err.println("Gateway decision rejected: HTTP " + response.statusCode() + " " + response.body());
                else System.out.println("CONTROL_DECISION " + body);
            } catch (Exception error) { System.err.println("Gateway control unavailable: " + error.getMessage()); }
        }
    }
    static final class ClickHouseSink {
        private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS traffic_windows ("
            + "window_start DateTime64(3, 'UTC'), window_end DateTime64(3, 'UTC'), baseline_scope LowCardinality(String) DEFAULT 'gateway', backend LowCardinality(String), "
            + "requests UInt64, requests_per_second Float64, mean_inter_request_ms Float64, mean_request_bytes Float64, "
            + "mean_processing_ms Float64, error_rate Float64, unique_endpoints UInt32, endpoint_repeatability Float64, method_counts String, "
            + "feature_vector Array(Float64), standardized_vector Array(Float64), standardization_samples UInt64, standardization_ready UInt8, "
            + "baseline_mean Array(Float64), baseline_stddev Array(Float64), covariance_matrix Array(Array(Float64)), baseline_samples UInt64, anomaly_score Float64, anomaly_level LowCardinality(String), baseline_source LowCardinality(String)) ENGINE = MergeTree PARTITION BY toYYYYMM(window_start) ORDER BY (baseline_scope, backend, window_start) "
            + "TTL window_end + INTERVAL 90 DAY DELETE";
        private final String url; private final HttpClient client = HttpClient.newHttpClient();
        static ClickHouseSink fromEnvironment() {
            String address = System.getenv("CLICKHOUSE_URL");
            if (address == null || address.isBlank()) { System.out.println("ClickHouse disabled: set CLICKHOUSE_URL to enable persistent history"); return new ClickHouseSink(null); }
            ClickHouseSink sink = new ClickHouseSink(address.replaceAll("/+$", "")); sink.execute(CREATE_TABLE); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS baseline_scope LowCardinality(String) DEFAULT 'gateway'"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS feature_vector Array(Float64)"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS standardized_vector Array(Float64)"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS standardization_samples UInt64"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS standardization_ready UInt8"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS baseline_mean Array(Float64)"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS baseline_stddev Array(Float64)"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS covariance_matrix Array(Array(Float64))"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS baseline_samples UInt64"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS anomaly_score Float64 DEFAULT 0"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS anomaly_level LowCardinality(String) DEFAULT 'warming_up'"); sink.execute("ALTER TABLE traffic_windows ADD COLUMN IF NOT EXISTS baseline_source LowCardinality(String) DEFAULT 'online'"); return sink;
        }
        ClickHouseSink(String url) { this.url = url; }
        void insert(String json) { if (url != null) execute("INSERT INTO traffic_windows FORMAT JSONEachRow", json); }
        void execute(String query) { execute(query, ""); }
        void execute(String query, String body) {
            if (url == null) return;
            try {
                String target = url + (url.contains("?") ? "&query=" : "/?query=") + URLEncoder.encode(query, StandardCharsets.UTF_8);
                HttpRequest request = HttpRequest.newBuilder(URI.create(target)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) System.err.println("ClickHouse write failed: HTTP " + response.statusCode() + " " + response.body());
            } catch (Exception error) { System.err.println("ClickHouse unavailable; vector remains in log only: " + error.getMessage()); }
        }
    }
}
