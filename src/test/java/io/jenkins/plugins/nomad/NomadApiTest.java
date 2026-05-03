package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class NomadApiTest {
    @Test
    public void pingsLeaderEndpoint() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/status/leader", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            writeResponse(exchange, 200, "\"127.0.0.1:4647\"");
        });
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            String leader = api.ping();
            assertEquals("GET", method.get());
            assertEquals("/v1/status/leader", path.get());
            assertEquals("\"127.0.0.1:4647\"", leader);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void submitsExpectedRequestAndParsesResponse() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> region = new AtomicReference<>();
        AtomicReference<String> namespace = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/jobs", exchange -> {
            captureRequest(exchange, method, path, region, namespace, token, contentType, body);
            writeResponse(exchange, 200, "{\"EvalID\":\"eval-123\",\"JobID\":\"job-456\",\"JobModifyIndex\":7}");
        });
        server.start();

        try {
            String payload = "{\"Job\":{\"Name\":\"agent\"}}";
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");

            NomadApi.SubmitResult result = api.submitJob(payload);

            assertEquals("POST", method.get());
            assertEquals("/v1/jobs", path.get());
            assertEquals("global", region.get());
            assertEquals("default", namespace.get());
            assertEquals(null, token.get());
            assertTrue(contentType.get().startsWith("application/json"));
            assertEquals(payload, body.get());
            assertEquals("eval-123", result.evaluationId());
            assertEquals("job-456", result.jobId());
            assertEquals(Long.valueOf(7L), result.jobModifyIndex());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void sendsAclTokenHeaderWhenConfigured() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/jobs", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Nomad-Token"));
            writeResponse(exchange, 200, "{\"EvalID\":\"eval-1\",\"JobID\":\"job-1\",\"JobModifyIndex\":1}");
        });
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default", "token-abc");
            api.submitJob("{\"Job\":{}}");
            assertEquals("token-abc", token.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void failsOnNonSuccessResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/jobs", exchange -> writeResponse(exchange, 500, "boom"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            try {
                api.submitJob("{\"Job\":{}}");
            } catch (IOException exception) {
                assertTrue(exception.getMessage().contains("status 500"));
                assertTrue(exception.getMessage().contains("boom"));
                return;
            }
            throw new AssertionError("Expected IOException for failing Nomad response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void stopsAndPurgesJob() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-abc", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            query.set(exchange.getRequestURI().getQuery());
            writeResponse(exchange, 200, "{}");
        });
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            api.stopJob("job-abc", true);

            assertEquals("DELETE", method.get());
            assertEquals("/v1/job/job-abc", path.get());
            assertEquals("purge=true", query.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void failsOnStopNonSuccessResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-bad", exchange -> writeResponse(exchange, 500, "cannot stop"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            try {
                api.stopJob("job-bad", true);
            } catch (IOException exception) {
                assertTrue(exception.getMessage().contains("stop failed"));
                assertTrue(exception.getMessage().contains("cannot stop"));
                return;
            }
            throw new AssertionError("Expected IOException for failing Nomad stop response");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void findsRunningAllocationId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-1/allocations", exchange -> writeResponse(
                exchange,
                200,
                "[{\"ID\":\"alloc-dead\",\"ClientStatus\":\"failed\"},{\"ID\":\"alloc-running\",\"ClientStatus\":\"running\"}]"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            assertEquals("alloc-running", api.findRunningAllocationId("job-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void findsRunningAllocationWithNodeId() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-1b/allocations", exchange -> writeResponse(
                exchange,
                200,
                "[{\"ID\":\"alloc-dead\",\"NodeID\":\"node-a\",\"ClientStatus\":\"failed\"},{\"ID\":\"alloc-running\",\"NodeID\":\"node-b\",\"ClientStatus\":\"running\"}]"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            NomadApi.AllocationRef allocation = api.findRunningAllocation("job-1b");

            assertEquals("alloc-running", allocation.id());
            assertEquals("node-b", allocation.nodeId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void fallsBackToFirstAllocationWhenNoneRunning() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-2/allocations", exchange -> writeResponse(
                exchange,
                200,
                "[{\"ID\":\"alloc-a\",\"ClientStatus\":\"complete\"},{\"ID\":\"alloc-b\",\"ClientStatus\":\"failed\"}]"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            assertEquals("alloc-a", api.findRunningAllocationId("job-2"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void returnsNullWhenAllocationResponseIsNotArray() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/job/job-3/allocations", exchange -> writeResponse(exchange, 200, "{}"));
        server.start();

        try {
            NomadApi api = new NomadApi("http://127.0.0.1:" + server.getAddress().getPort(), "global", "default");
            assertNull(api.findRunningAllocationId("job-3"));
        } finally {
            server.stop(0);
        }
    }

    private static void captureRequest(
            HttpExchange exchange,
            AtomicReference<String> method,
            AtomicReference<String> path,
            AtomicReference<String> region,
            AtomicReference<String> namespace,
            AtomicReference<String> token,
            AtomicReference<String> contentType,
            AtomicReference<String> body)
            throws IOException {
        method.set(exchange.getRequestMethod());
        path.set(exchange.getRequestURI().getPath());
        region.set(exchange.getRequestHeaders().getFirst("X-Nomad-Region"));
        namespace.set(exchange.getRequestHeaders().getFirst("X-Nomad-Namespace"));
        token.set(exchange.getRequestHeaders().getFirst("X-Nomad-Token"));
        contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        body.set(readBody(exchange.getRequestBody()));
    }

    private static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeResponse(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
