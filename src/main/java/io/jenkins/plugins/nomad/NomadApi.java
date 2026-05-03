package io.jenkins.plugins.nomad;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class NomadApi {
    private static final Pattern STRING_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern LONG_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");

    private final String address;
    private final String region;
    private final String namespace;
    private final String aclToken;
    private final boolean skipTlsVerify;
    private final Transport transport;

    public NomadApi(String address, String region, String namespace) {
        this(address, region, namespace, null, false, defaultTransport(false));
    }

    public NomadApi(String address, String region, String namespace, String aclToken) {
        this(address, region, namespace, aclToken, false, defaultTransport(false));
    }

    public NomadApi(String address, String region, String namespace, String aclToken, boolean skipTlsVerify) {
        this(address, region, namespace, aclToken, skipTlsVerify, defaultTransport(skipTlsVerify));
    }

    NomadApi(String address, String region, String namespace, String aclToken, boolean skipTlsVerify, Transport transport) {
        this.address = address == null || address.isBlank() ? "http://127.0.0.1:4646" : address;
        this.region = region == null || region.isBlank() ? "global" : region;
        this.namespace = namespace == null || namespace.isBlank() ? "default" : namespace;
        this.aclToken = aclToken == null || aclToken.isBlank() ? null : aclToken;
        this.skipTlsVerify = skipTlsVerify;
        this.transport = transport;
    }

    HttpRequest.Builder requestBuilder(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(address + path))
                .header("X-Nomad-Region", region)
                .header("X-Nomad-Namespace", namespace);
        if (aclToken != null) {
            builder.header("X-Nomad-Token", aclToken);
        }
        return builder;
    }

    HttpRequest buildSubmitJobRequest(String payload) {
        return requestBuilder("/v1/jobs")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    public String ping() throws IOException, InterruptedException {
        HttpRequest request = requestBuilder("/v1/status/leader").GET().build();
        HttpResponse<String> response = transport.send(request);
        String responseBody = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nomad connectivity check failed with status " + response.statusCode() + ": " + responseBody);
        }
        return responseBody;
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    public SubmitResult submitJob(String payload) throws IOException, InterruptedException {
        HttpResponse<String> response = transport.send(buildSubmitJobRequest(payload));
        String responseBody = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nomad job submission failed with status " + response.statusCode() + ": " + responseBody);
        }
        return new SubmitResult(
                response.statusCode(),
                extractStringField(responseBody, "EvalID").orElse(null),
                extractStringField(responseBody, "JobID").orElse(null),
                extractLongField(responseBody, "JobModifyIndex").orElse(null),
                responseBody);
    }

    HttpRequest buildStopJobRequest(String jobId, boolean purge) {
        String encodedJobId = URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        return requestBuilder("/v1/job/" + encodedJobId + "?purge=" + purge)
                .DELETE()
                .build();
    }

    public void stopJob(String jobId, boolean purge) throws IOException, InterruptedException {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }
        HttpResponse<String> response = transport.send(buildStopJobRequest(jobId, purge));
        String responseBody = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nomad job stop failed with status " + response.statusCode() + ": " + responseBody);
        }
    }

    HttpRequest buildJobAllocationsRequest(String jobId) {
        String encodedJobId = URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        return requestBuilder("/v1/job/" + encodedJobId + "/allocations")
                .GET()
                .build();
    }

    HttpRequest buildAllocationRequest(String allocationId) {
        String encodedAllocationId = URLEncoder.encode(allocationId, StandardCharsets.UTF_8);
        return requestBuilder("/v1/allocation/" + encodedAllocationId)
                .GET()
                .build();
    }

    public AllocationRef findRunningAllocation(String jobId) throws IOException, InterruptedException {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId is required");
        }

        HttpResponse<String> response = transport.send(buildJobAllocationsRequest(jobId));
        String responseBody = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nomad allocation lookup failed with status " + response.statusCode() + ": " + responseBody);
        }

        AllocationRef fallbackAllocation = null;
        for (String allocationObject : topLevelObjects(responseBody)) {
            String topLevelSlice = sliceBeforeTaskStates(allocationObject);
            String allocationId = extractStringField(topLevelSlice, "ID").orElse(null);
            if (allocationId == null || allocationId.isBlank()) {
                continue;
            }

            AllocationRef allocation = new AllocationRef(
                    allocationId,
                    extractStringField(topLevelSlice, "NodeID").orElse(null));

            if (fallbackAllocation == null) {
                fallbackAllocation = allocation;
            }

            String status = extractStringField(topLevelSlice, "ClientStatus").orElse("");
            if ("running".equalsIgnoreCase(status)) {
                return allocation;
            }
        }
        return fallbackAllocation;
    }

    public String findRunningAllocationId(String jobId) throws IOException, InterruptedException {
        AllocationRef allocation = findRunningAllocation(jobId);
        return allocation == null ? null : allocation.id();
    }

    public boolean isTaskRunning(String allocationId, String taskName) throws IOException, InterruptedException {
        if (allocationId == null || allocationId.isBlank()) {
            throw new IllegalArgumentException("allocationId is required");
        }
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("taskName is required");
        }

        HttpResponse<String> response = transport.send(buildAllocationRequest(allocationId));
        String responseBody = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Nomad allocation read failed with status " + response.statusCode() + ": " + responseBody);
        }

        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(taskName) + "\\\"\\s*:\\s*\\{(?s).*?\\\"State\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(responseBody);
        if (!matcher.find()) {
            return false;
        }
        return "running".equalsIgnoreCase(matcher.group(1));
    }

    private static String sliceBeforeTaskStates(String allocationObject) {
        int taskStatesIndex = allocationObject.indexOf("\"TaskStates\"");
        return taskStatesIndex > 0 ? allocationObject.substring(0, taskStatesIndex) : allocationObject;
    }

    private static Iterable<String> topLevelObjects(String payload) {
        java.util.List<String> objects = new java.util.ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return objects;
        }

        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int index = 0; index < payload.length(); index++) {
            char ch = payload.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == '{') {
                if (depth == 0) {
                    start = index;
                }
                depth++;
                continue;
            }

            if (ch == '}') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && start >= 0) {
                        objects.add(payload.substring(start, index + 1));
                        start = -1;
                    }
                }
            }
        }

        return objects;
    }

    private static Transport defaultTransport(boolean skipTlsVerify) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (skipTlsVerify) {
            try {
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(
                        null,
                        new TrustManager[] {new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}

                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[0];
                            }
                        }},
                        new SecureRandom());
                SSLParameters sslParameters = new SSLParameters();
                sslParameters.setEndpointIdentificationAlgorithm("");
                builder.sslContext(sslContext).sslParameters(sslParameters);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("Failed to configure insecure TLS client", exception);
            }
        }
        HttpClient client = builder.build();
        return request -> client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> extractStringField(String payload, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(STRING_FIELD_PATTERN.pattern(), Pattern.quote(fieldName))).matcher(payload);
        return matcher.find() ? Optional.ofNullable(matcher.group(1)) : Optional.empty();
    }

    private static Optional<Long> extractLongField(String payload, String fieldName) {
        Matcher matcher = Pattern.compile(String.format(LONG_FIELD_PATTERN.pattern(), Pattern.quote(fieldName))).matcher(payload);
        return matcher.find() ? Optional.of(Long.parseLong(matcher.group(1))) : Optional.empty();
    }

    @FunctionalInterface
    interface Transport {
        HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
    }

    public record SubmitResult(
            int statusCode,
            String evaluationId,
            String jobId,
            Long jobModifyIndex,
            String body) {}

    public record AllocationRef(String id, String nodeId) {}
}
