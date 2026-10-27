package spring.chat.api;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client for the Spring Chat Plugin API. Requires Java 11 or newer.
 * Each method returns the server's JSON reply as a String.
 */
public final class SpringChatPlugin {

    /** Thrown when the server answers with a status other than 200. */
    public static class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String body) {
            super("Spring Chat returned " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final URI baseUri;
    private final String key;
    private final HttpClient http;

    public SpringChatPlugin(String baseUrl, String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("A plugin key is required");
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUri = URI.create(base);
        this.key = key;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public SpringChatPlugin(String key) {
        this("http://localhost:8080", key);
    }

    /** Returns this plugin's name, group id and group name as JSON. */
    public String info() throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(baseUri.resolve("/api/plugin/info")).GET());
    }

    /** Posts a text message (up to 2000 characters) and returns the stored message as JSON. */
    public String postMessage(String text) throws IOException, InterruptedException {
        String body = "{\"text\": " + jsonString(text) + "}";
        return send(HttpRequest.newBuilder(baseUri.resolve("/api/plugin/messages"))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private String send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpRequest request = builder
                .header("Content-Type", "application/json")
                .header("X-Plugin-Key", key)
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new ApiException(response.statusCode(), response.body());
        }
        return response.body();
    }

    /** Quotes and escapes a string so it can be placed inside JSON. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
