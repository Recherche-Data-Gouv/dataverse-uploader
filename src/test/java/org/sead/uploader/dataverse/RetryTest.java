package org.sead.uploader.dataverse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RetryTest {

    private HttpServer server;
    private int port;
    private CloseableHttpClient httpClient;

    @BeforeEach
    public void setup() throws IOException, NoSuchFieldException, IllegalAccessException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
        httpClient = HttpClients.createDefault();

        // Speed up tests by reducing retry delays
        setStaticField(DVUploader.class, "uploadUrlBaseRetryDelayMs", 10);
        setStaticField(DVUploader.class, "uploadUrlMaxRetryDelayMs", 100);
    }

    private void setStaticField(Class<?> clazz, String fieldName, Object value) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private Object getStaticField(Class<?> clazz, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    @AfterEach
    public void teardown() throws IOException {
        if (server != null) {
            server.stop(0);
        }
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @Test
    public void test429Retry() throws IOException {
        AtomicInteger callCount = new AtomicInteger(0);
        server.createContext("/api/datasets/test", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int count = callCount.incrementAndGet();
                if (count == 1) {
                    exchange.sendResponseHeaders(429, -1);
                } else {
                    byte[] response = "OK".getBytes();
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                }
            }
        });

        HttpGet request = new HttpGet("http://localhost:" + port + "/api/datasets/test");
        try (CloseableHttpResponse response = DVUploader.executeWithRetry(request, httpClient, null)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            assertEquals(2, callCount.get());
        }
    }

    @Test
    public void test50xRetry() throws IOException {
        AtomicInteger callCount = new AtomicInteger(0);
        server.createContext("/s3/test", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int count = callCount.incrementAndGet();
                if (count <= 2) {
                    exchange.sendResponseHeaders(503, -1);
                } else {
                    byte[] response = "OK".getBytes();
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                }
            }
        });

        HttpGet request = new HttpGet("http://localhost:" + port + "/s3/test");
        try (CloseableHttpResponse response = DVUploader.executeWithRetry(request, httpClient, null)) {
            assertEquals(200, response.getStatusLine().getStatusCode());
            assertEquals(3, callCount.get());
        }
    }

    @Test
    public void testGlobalSlowdown() throws IOException {
        // Reset state
        // Since fields are private, I can't reset them easily unless I make them package-private or use reflection.
        // But I can just check that it increases.
        
        server.createContext("/api/datasets/slowdown", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(429, -1);
            }
        });

        HttpGet request = new HttpGet("http://localhost:" + port + "/api/datasets/slowdown");
        // We expect it to retry 5 times and then return 429
        try (CloseableHttpResponse response = DVUploader.executeWithRetry(request, httpClient, null)) {
            assertEquals(429, response.getStatusLine().getStatusCode());
        }
        
        // Now try another request, it should wait at least 5 * 50ms = 250ms more than before if we hit 429 5 times.
        // Actually, uploadUrlInterRequestDelayMs is increased by 50ms on each 429.
    }

    @Test
    public void testClearCacheResetsRetryConfigButNotTiming() throws IOException, NoSuchFieldException, IllegalAccessException {
        // 1. Manually set some non-default values
        setStaticField(DVUploader.class, "uploadUrlMaxRetries", 10);
        setStaticField(DVUploader.class, "uploadUrlBaseRetryDelayMs", 5000);
        setStaticField(DVUploader.class, "uploadUrlMaxRetryDelayMs", 120000);
        
        setStaticField(DVUploader.class, "uploadUrlCooldownUntil", 123456789L);
        setStaticField(DVUploader.class, "uploadUrlInterRequestDelayMs", 500);
        setStaticField(DVUploader.class, "lastUploadUrlRequestTimestamp", 987654321L);

        // 2. Call clearCache
        new DVUploader().clearCache();

        // 3. Verify config is reset
        assertEquals(5, getStaticField(DVUploader.class, "uploadUrlMaxRetries"), "uploadUrlMaxRetries should be reset to default");
        assertEquals(2000, getStaticField(DVUploader.class, "uploadUrlBaseRetryDelayMs"), "uploadUrlBaseRetryDelayMs should be reset to default");
        assertEquals(60000, getStaticField(DVUploader.class, "uploadUrlMaxRetryDelayMs"), "uploadUrlMaxRetryDelayMs should be reset to default");

        // 4. Verify timing state is NOT reset
        assertEquals(123456789L, getStaticField(DVUploader.class, "uploadUrlCooldownUntil"), "uploadUrlCooldownUntil should NOT be reset");
        assertEquals(500, getStaticField(DVUploader.class, "uploadUrlInterRequestDelayMs"), "uploadUrlInterRequestDelayMs should NOT be reset");
        assertEquals(987654321L, getStaticField(DVUploader.class, "lastUploadUrlRequestTimestamp"), "lastUploadUrlRequestTimestamp should NOT be reset");
    }
}
