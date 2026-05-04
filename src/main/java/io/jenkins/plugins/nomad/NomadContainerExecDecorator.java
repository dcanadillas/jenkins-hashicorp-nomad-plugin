package io.jenkins.plugins.nomad;

import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import jenkins.model.Jenkins;

/**
 * Bridges Jenkins process launches into Nomad sidecar tasks when running inside a
 * {@code nomadContainer(...)} scope.
 */
public class NomadContainerExecDecorator extends LauncherDecorator implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(NomadContainerExecDecorator.class.getName());
    private static final String TASK_START_TIMEOUT_SECONDS_PROPERTY = "io.jenkins.plugins.nomad.taskStartTimeoutSeconds";
    private static final long DEFAULT_TASK_START_TIMEOUT_SECONDS = 600L;
    // Wait budget for durable script files to appear in sidecars during first-run image pulls.
    private static final String DURABLE_SCRIPT_WAIT_SECONDS_PROPERTY = "io.jenkins.plugins.nomad.durableScriptWaitSeconds";
    private static final long DEFAULT_DURABLE_SCRIPT_WAIT_SECONDS = 180L;
    private static final long DURABLE_SCRIPT_WAIT_POLL_MILLIS = 50L;
    private static final Pattern STDOUT_DATA_PATTERN = Pattern.compile("\"stdout\"\\s*:\\s*\\{[^}]*\"data\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern STDERR_DATA_PATTERN = Pattern.compile("\"stderr\"\\s*:\\s*\\{[^}]*\"data\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("\"exit_code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern DURABLE_DIR_QUOTED_PATH_PATTERN = Pattern.compile("['\"]([^'\"]*durable-[^/'\"]+)/(?:script\\.sh(?:\\.copy)?|jenkins-(?:log|result)\\.txt(?:\\.tmp)?)['\"]");
    private static final Pattern DURABLE_DIR_FALLBACK_PATTERN = Pattern.compile("(/[^'\"\\s]*durable-[^/'\"\\s]+)");

    private final String containerName;

    public NomadContainerExecDecorator(String containerName) {
        this.containerName = containerName;
    }

    @Override
    public Launcher decorate(Launcher launcher, Node node) {
        if (!(node instanceof NomadAgent nomadAgent)) {
            LOGGER.info("[nomadContainer] decorate bypass: node is not NomadAgent ("
                    + (node == null ? "null" : node.getClass().getName()) + ")");
            return launcher;
        }
        if (containerName == null || containerName.isBlank() || "jnlp".equals(containerName)) {
            LOGGER.info("[nomadContainer] decorate bypass: container='" + containerName + "'");
            return launcher;
        }
        LOGGER.info("[nomadContainer] decorate active: container='" + containerName
                + "' node='" + nomadAgent.getNodeName() + "'");
        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                LOGGER.info("[nomadContainer] intercepted launch for container='" + containerName + "'");
                NomadCloud cloud = (NomadCloud) Jenkins.get().getCloud(nomadAgent.getCloudName());
                if (cloud == null) {
                    throw new IOException("Nomad cloud '" + nomadAgent.getCloudName() + "' not found");
                }
                if (nomadAgent.getNomadJobId() == null || nomadAgent.getNomadJobId().isBlank()) {
                    throw new IOException("Nomad job id is unavailable for container execution");
                }

                NomadApi.AllocationRef allocation;
                try {
                    allocation = cloud.connect().findRunningAllocation(nomadAgent.getNomadJobId());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while resolving Nomad allocation", exception);
                }

                if (allocation == null || allocation.id() == null || allocation.id().isBlank()) {
                    throw new IOException("No running allocation found for Nomad job '" + nomadAgent.getNomadJobId() + "'");
                }

                List<String> command = starter.cmds();
                if (command == null || command.isEmpty()) {
                    throw new IOException("Cannot execute empty command in nomadContainer context");
                }
                OutputStream stdout = starter.stdout() == null ? getListener().getLogger() : starter.stdout();
                OutputStream stderr = starter.stderr() == null ? stdout : starter.stderr();

                stdout.write(("[nomadContainer] exec allocation=" + allocation.id() + " task=" + containerName + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                stdout.flush();

                waitForTaskToBeRunning(cloud, allocation.id(), containerName, stdout);

                LOGGER.info("[nomadContainer] command argv=" + command);
                // Durable-task shells provide script/log/result paths; detect them to run a compact remote runner.
                String durableDir = findDurableDir(command);
                if (durableDir != null) {
                    LOGGER.info("[nomadContainer] durable dir detected: " + durableDir);
                    String scriptPath = durableDir + "/script.sh";
                    String scriptBase64 = readAgentFileAsBase64(starter, scriptPath);
                    prepareDurableLauncherInputs(
                        cloud,
                        allocation.id(),
                        allocation.nodeId(),
                        containerName,
                        durableDir,
                        scriptBase64,
                        stdout,
                        stderr);
                    command = buildDetachedDurableRunnerCommand(command, durableDir, containerName);
                    LOGGER.info("[nomadContainer] using compact durable runner command");
                }
                // Normalize workspace and CWD exported to the sidecar so Jenkins and container paths stay aligned.
                String workspacePath = resolveWorkspacePath(starter, durableDir);
                String workingDirPath = resolveWorkingDirectoryPath(starter);
                command = withExportedEnvironment(command, starter.envs(), workspacePath, workingDirPath);
                Proc proc = launchExecOverWebSocket(
                        cloud, allocation.id(), allocation.nodeId(), containerName, command, stdout, stderr, true);
                if (durableDir != null) {
                    mirrorDurableResultToAgentAsync(starter, durableDir, proc);
                }
                return proc;
            }
        };
    }

    private static void mirrorDurableResultToAgentAsync(Launcher.ProcStarter starter, String durableDir, Proc proc) {
        Thread mirrorThread = new Thread(() -> {
            int code;
            try {
                code = proc.join();
            } catch (Exception exception) {
                LOGGER.warning("[nomadContainer] unable to obtain remote durable exit code: " + exception.getMessage());
                return;
            }

            try {
                if (starter == null || starter.pwd() == null || starter.pwd().getChannel() == null) {
                    return;
                }
                FilePath resultPath = new FilePath(starter.pwd().getChannel(), durableDir + "/jenkins-result.txt");
                resultPath.write(Integer.toString(code) + "\n", StandardCharsets.UTF_8.name());

                FilePath logPath = new FilePath(starter.pwd().getChannel(), durableDir + "/jenkins-log.txt");
                if (!logPath.exists()) {
                    logPath.write("", StandardCharsets.UTF_8.name());
                }
                LOGGER.info("[nomadContainer] mirrored durable result to agent: " + resultPath.getRemote() + " code=" + code);
            } catch (Exception exception) {
                LOGGER.warning("[nomadContainer] unable to mirror durable result to agent: " + exception.getMessage());
            }
        }, "nomad-durable-result-mirror");
        mirrorThread.setDaemon(true);
        mirrorThread.start();
    }

    private static String findDurableDir(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }

        for (String part : command) {
            if (part == null || part.isBlank()) {
                continue;
            }
            Matcher quotedMatcher = DURABLE_DIR_QUOTED_PATH_PATTERN.matcher(part);
            if (quotedMatcher.find()) {
                return quotedMatcher.group(1);
            }
        }

        String joined = String.join(" ", command);
        Matcher fallbackMatcher = DURABLE_DIR_FALLBACK_PATTERN.matcher(joined);
        if (fallbackMatcher.find()) {
            return fallbackMatcher.group(1);
        }
        return null;
    }

    private static void prepareDurableLauncherInputs(
            NomadCloud cloud,
            String allocationId,
            String nodeId,
            String container,
            String durableDir,
            String scriptBase64,
            OutputStream stdout,
            OutputStream stderr) {
        String scriptPath = durableDir + "/script.sh";
        String scriptCopyPath = durableDir + "/script.sh.copy";
        String marker = durableDir + "/jenkins-log.txt";
        long durableScriptWaitAttempts = resolveDurableScriptWaitAttempts();
        String command;
        if (scriptBase64 != null && !scriptBase64.isBlank()) {
            command = "mkdir -p \"" + durableDir + "\""
                    + " && echo '" + scriptBase64 + "' | base64 -d > \"" + scriptPath + "\""
                    + " && cp \"" + scriptPath + "\" \"" + scriptCopyPath + "\""
                    + " && : >> \"" + marker + "\"";
        } else {
            command = "mkdir -p \"" + durableDir + "\""
                    + " && i=0"
                + " && while [ $i -lt " + durableScriptWaitAttempts + " ] && [ ! -f \"" + scriptPath + "\" ]; do i=$((i+1)); sleep 0.05; done"
                    + " && [ -f \"" + scriptPath + "\" ]"
                    + " && cp \"" + scriptPath + "\" \"" + scriptCopyPath + "\""
                    + " && : >> \"" + marker + "\"";
        }
        try {
            Proc markerProc = launchExecOverWebSocket(
                    cloud,
                    allocationId,
                    nodeId,
                    container,
                    List.of("/bin/sh", "-lc", command),
                    stdout,
                stderr,
                false);
            int prepCode = markerProc.join();
            if (prepCode != 0) {
                throw new IOException("durable prep command exited with code " + prepCode);
            }
            LOGGER.info("[nomadContainer] durable launcher inputs prepared: script=" + scriptPath
                + " copy=" + scriptCopyPath + " marker=" + marker);
        } catch (Exception exception) {
            LOGGER.warning("[nomadContainer] unable to prepare durable launcher inputs: " + exception.getMessage());
        }
    }

    private static String readAgentFileAsBase64(Launcher.ProcStarter starter, String absolutePath) {
        try {
            if (starter == null || starter.pwd() == null || starter.pwd().getChannel() == null) {
                return null;
            }
            FilePath scriptPath = new FilePath(starter.pwd().getChannel(), absolutePath);
            if (!scriptPath.exists()) {
                LOGGER.info("[nomadContainer] durable script not yet visible on agent channel: " + absolutePath);
                return null;
            }
            try (InputStream input = scriptPath.read()) {
                byte[] bytes = input.readAllBytes();
                return Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception exception) {
            LOGGER.warning("[nomadContainer] unable to read durable script from agent channel: " + exception.getMessage());
            return null;
        }
    }

    private static List<String> buildDetachedDurableRunnerCommand(
            List<String> originalCommand, String durableDir, String activeContainerName) {
        String scriptPath = durableDir + "/script.sh";
        String scriptCopyPath = durableDir + "/script.sh.copy";
        String logPath = durableDir + "/jenkins-log.txt";
        String resultTmpPath = durableDir + "/jenkins-result.txt.tmp";
        String resultPath = durableDir + "/jenkins-result.txt";
        String serverCookie = findDurableCookie(originalCommand);
        long durableScriptWaitAttempts = resolveDurableScriptWaitAttempts();

        StringBuilder command = new StringBuilder();
        if (serverCookie != null && !serverCookie.isBlank()) {
            command.append("export JENKINS_SERVER_COOKIE=")
                .append(shellSingleQuote(serverCookie))
                .append("; ");
        }
        if (activeContainerName != null && !activeContainerName.isBlank()) {
            command.append("export ")
                .append(NomadPipelineContext.ACTIVE_CONTAINER_ENV)
                .append("=")
                .append(shellSingleQuote(activeContainerName))
                .append("; ");
        }
        command.append("mkdir -p ")
            .append(shellSingleQuote(durableDir))
            .append("; if [ ! -f ")
            .append(shellSingleQuote(scriptCopyPath))
            .append(" ]; then i=0; while [ $i -lt ")
            .append(durableScriptWaitAttempts)
            .append(" ] && [ ! -f ")
            .append(shellSingleQuote(scriptCopyPath))
            .append(" ] && [ ! -f ")
            .append(shellSingleQuote(scriptPath))
            .append(" ]; do i=$((i+1)); sleep 0.05; done; if [ ! -f ")
            .append(shellSingleQuote(scriptCopyPath))
            .append(" ] && [ -f ")
            .append(shellSingleQuote(scriptPath))
            .append(" ]; then cp ")
            .append(shellSingleQuote(scriptPath))
            .append(" ")
            .append(shellSingleQuote(scriptCopyPath))
            .append("; fi; fi; if [ ! -f ")
            .append(shellSingleQuote(scriptCopyPath))
            .append(" ]; then echo 'durable script unavailable' > ")
            .append(shellSingleQuote(logPath))
            .append(" 2>&1; code=1; echo $code > ")
            .append(shellSingleQuote(resultTmpPath))
            .append("; mv ")
            .append(shellSingleQuote(resultTmpPath))
            .append(" ")
            .append(shellSingleQuote(resultPath))
            .append("; cat ")
            .append(shellSingleQuote(logPath))
            .append("; exit $code; fi; sh -xe ")
            .append(shellSingleQuote(scriptCopyPath))
            .append(" > ")
            .append(shellSingleQuote(logPath))
            .append(" 2>&1; code=$?; cat ")
            .append(shellSingleQuote(logPath))
            .append("; echo $code > ")
            .append(shellSingleQuote(resultTmpPath))
            .append("; mv ")
            .append(shellSingleQuote(resultTmpPath))
            .append(" ")
            .append(shellSingleQuote(resultPath))
            .append("; exit $code");

        return List.of("/bin/sh", "-c", command.toString());
    }

    private static void waitForTaskToBeRunning(
            NomadCloud cloud,
            String allocationId,
            String taskName,
            OutputStream stdout) throws IOException {
        if (taskName == null || taskName.isBlank() || "jnlp".equals(taskName)) {
            return;
        }

        long timeoutSeconds = Long.getLong(TASK_START_TIMEOUT_SECONDS_PROPERTY, DEFAULT_TASK_START_TIMEOUT_SECONDS);
        if (timeoutSeconds <= 0) {
            timeoutSeconds = DEFAULT_TASK_START_TIMEOUT_SECONDS;
        }
        long timeoutMs = Duration.ofSeconds(timeoutSeconds).toMillis();
        long pollMs = 500L;
        long deadline = System.currentTimeMillis() + timeoutMs;
        int attempt = 0;

        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                if (cloud.connect().isTaskRunning(allocationId, taskName)) {
                    LOGGER.info("[nomadContainer] task '" + taskName + "' is running in allocation '" + allocationId + "'");
                    return;
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Nomad task startup", interruptedException);
            }

            if (attempt == 1 || attempt % 10 == 0) {
                String waitMessage = "[nomadContainer] waiting for task '" + taskName + "' to start...\n";
                try {
                    stdout.write(waitMessage.getBytes(StandardCharsets.UTF_8));
                    stdout.flush();
                } catch (IOException ignore) {
                }
                LOGGER.info("[nomadContainer] task '" + taskName + "' not running yet; startup wait attempt " + attempt);
            }

            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for Nomad task startup", interruptedException);
            }
        }

        throw new IOException("Nomad task '" + taskName + "' did not reach running state within "
                + Duration.ofMillis(timeoutMs).toSeconds() + "s (allocation=" + allocationId + ")");
    }

    private static String findDurableCookie(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String joined = String.join(" ", command);
        Matcher matcher = Pattern.compile("jsc=([A-Za-z0-9._-]+)").matcher(joined);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long resolveDurableScriptWaitAttempts() {
        // Translate wait seconds to polling attempts used by shell loops.
        long waitSeconds = Long.getLong(DURABLE_SCRIPT_WAIT_SECONDS_PROPERTY, DEFAULT_DURABLE_SCRIPT_WAIT_SECONDS);
        if (waitSeconds <= 0) {
            waitSeconds = DEFAULT_DURABLE_SCRIPT_WAIT_SECONDS;
        }
        return Math.max(1L, Duration.ofSeconds(waitSeconds).toMillis() / DURABLE_SCRIPT_WAIT_POLL_MILLIS);
    }

    private static String shellSingleQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static List<String> withExportedEnvironment(
            List<String> command,
            String[] envEntries,
            String workspacePath,
            String workingDirPath) {
        if (command == null || command.isEmpty()) {
            return command;
        }

        String prefix = buildWorkingDirectoryPrefix(workingDirPath) + buildExportPrefix(envEntries, workspacePath, workingDirPath);
        if (prefix.isBlank()) {
            return command;
        }

        String shellCommand = command.stream()
                .map(NomadContainerExecDecorator::shellSingleQuote)
                .collect(Collectors.joining(" "));
        return List.of("/bin/sh", "-lc", prefix + "exec " + shellCommand);
    }

    private static String buildExportPrefix(String[] envEntries, String workspacePath, String workingDirPath) {
        StringBuilder exports = new StringBuilder();
        if (envEntries != null) {
            for (String entry : envEntries) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                int separator = entry.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String name = entry.substring(0, separator);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    continue;
                }
                if ("PATH".equals(name) || "WORKSPACE".equals(name) || "WORKSPACE_TMP".equals(name)) {
                    continue;
                }
                String value = entry.substring(separator + 1);
                exports.append("export ")
                        .append(name)
                        .append('=')
                        .append(shellSingleQuote(value))
                        .append("; ");
            }
        }

        String normalizedWorkspacePath = workspacePath == null || workspacePath.isBlank() ? null : workspacePath;
        String normalizedWorkingDirPath = workingDirPath == null || workingDirPath.isBlank() ? null : workingDirPath;
        if (normalizedWorkspacePath != null || normalizedWorkingDirPath != null) {
            if (normalizedWorkspacePath != null
                    && (normalizedWorkingDirPath == null || normalizedWorkspacePath.equals(normalizedWorkingDirPath))) {
                exports.append("if [ -d ")
                        .append(shellSingleQuote(normalizedWorkspacePath))
                        .append(" ]; then export WORKSPACE=")
                        .append(shellSingleQuote(normalizedWorkspacePath))
                        .append("; else export WORKSPACE=\"$(pwd)\"; fi; ");
            } else if (normalizedWorkspacePath == null) {
                exports.append("if [ -d ")
                        .append(shellSingleQuote(normalizedWorkingDirPath))
                        .append(" ]; then export WORKSPACE=")
                        .append(shellSingleQuote(normalizedWorkingDirPath))
                        .append("; else export WORKSPACE=\"$(pwd)\"; fi; ");
            } else {
                exports.append("if [ -d ")
                        .append(shellSingleQuote(normalizedWorkspacePath))
                        .append(" ]; then export WORKSPACE=")
                        .append(shellSingleQuote(normalizedWorkspacePath))
                        .append("; elif [ -d ")
                        .append(shellSingleQuote(normalizedWorkingDirPath))
                        .append(" ]; then export WORKSPACE=")
                        .append(shellSingleQuote(normalizedWorkingDirPath))
                        .append("; else export WORKSPACE=\"$(pwd)\"; fi; ");
            }
            exports.append("export WORKSPACE_TMP=\"${WORKSPACE}@tmp\"; ");
        }
        return exports.toString();
    }

    private static String buildWorkingDirectoryPrefix(String workingDirPath) {
        if (workingDirPath == null || workingDirPath.isBlank()) {
            return "";
        }
        return "mkdir -p " + shellSingleQuote(workingDirPath)
                + "; cd " + shellSingleQuote(workingDirPath)
                + "; ";
    }

    private static String resolveWorkspacePath(Launcher.ProcStarter starter, String durableDir) {
        String fromStarter = resolveWorkingDirectoryPath(starter);
        if (fromStarter != null) {
            return fromStarter;
        }
        String derivedFromDurable = workspaceFromDurableDir(durableDir);
        if (derivedFromDurable != null) {
            return derivedFromDurable;
        }
        if (starter == null || starter.pwd() == null) {
            return null;
        }
        return starter.pwd().getRemote();
    }

    private static String resolveWorkingDirectoryPath(Launcher.ProcStarter starter) {
        if (starter == null || starter.pwd() == null) {
            return null;
        }
        return starter.pwd().getRemote();
    }

    private static String workspaceFromDurableDir(String durableDir) {
        if (durableDir == null || durableDir.isBlank()) {
            return null;
        }
        int marker = durableDir.indexOf("@tmp/");
        if (marker <= 0) {
            return null;
        }
        return durableDir.substring(0, marker);
    }

    private static Proc launchExecOverWebSocket(
            NomadCloud cloud,
            String allocationId,
            String nodeId,
            String container,
            List<String> command,
            OutputStream stdout,
            OutputStream stderr,
            boolean allowAbnormalCloseSuccess) throws IOException {
        URI execUri = buildExecUri(cloud, allocationId, nodeId, container, command);
        HttpClient client = buildWebSocketClient(cloud.isSkipTlsVerify());

        int maxAttempts = 8;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LOGGER.info("[nomadContainer] connecting WebSocket: " + execUri + " (attempt " + attempt + "/" + maxAttempts + ")");
            CompletableFuture<Integer> exitCode = new CompletableFuture<>();
            NomadExecListener listener = new NomadExecListener(exitCode, stdout, stderr, allowAbnormalCloseSuccess);

            try {
                WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15));
                if (cloud.getRegion() != null && !cloud.getRegion().isBlank()) {
                    builder.header("X-Nomad-Region", cloud.getRegion());
                }
                if (cloud.getNamespace() != null && !cloud.getNamespace().isBlank()) {
                    builder.header("X-Nomad-Namespace", cloud.getNamespace());
                }
                String token = cloud.resolveAclToken();
                if (token != null && !token.isBlank()) {
                    builder.header("X-Nomad-Token", token);
                }
                WebSocket webSocket = builder.buildAsync(execUri, listener).join();

                LOGGER.info("[nomadContainer] command sent in URI, waiting for exit");
                NomadExecProc proc = new NomadExecProc(webSocket, exitCode);

                try {
                    Integer immediateExit = exitCode.get(350, TimeUnit.MILLISECONDS);
                    if (immediateExit != null && immediateExit != 0 && listener.isTaskNotStartedYet()) {
                        if (attempt == maxAttempts) {
                            LOGGER.warning("[nomadContainer] task still not started after retries for task='" + container + "'");
                            return proc;
                        }
                        long delayMs = Math.min(200L * attempt, 1000L);
                        LOGGER.info("[nomadContainer] task '" + container + "' not started yet, retrying in " + delayMs + "ms");
                        Thread.sleep(delayMs);
                        continue;
                    }
                    return proc;
                } catch (TimeoutException timeoutException) {
                    return proc;
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to retry Nomad exec", interruptedException);
            } catch (ExecutionException executionException) {
                Throwable cause = executionException.getCause() == null ? executionException : executionException.getCause();
                throw new IOException("Failed while checking Nomad exec startup state: " + cause, cause);
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof WebSocketHandshakeException handshakeException) {
                    int statusCode = handshakeException.getResponse() == null
                            ? -1
                            : handshakeException.getResponse().statusCode();
                    throw new IOException(
                            "Failed to open Nomad exec WebSocket (HTTP " + statusCode + ") to " + execUri,
                            cause);
                }
                throw new IOException("Failed to open Nomad exec WebSocket: " + cause, cause);
            }
        }
        throw new IOException("Failed to open Nomad exec WebSocket after retries");
    }

    private static URI buildExecUri(NomadCloud cloud, String allocationId, String nodeId, String task, List<String> command) {
        URI address = URI.create(cloud.getNomadAddress());
        String scheme = "https".equalsIgnoreCase(address.getScheme()) ? "wss" : "ws";
        String commandJson = toJsonArray(command == null ? List.of() : command);
        String query = "task=" + urlEncode(task)
            + "&tty=true"
            + "&ws_handshake=false"
                + (nodeId == null || nodeId.isBlank() ? "" : "&node_id=" + urlEncode(nodeId))
                + (commandJson.isBlank() ? "" : "&command=" + urlEncode(commandJson));
        return URI.create(
                scheme + "://" + address.getAuthority() + "/v1/client/allocation/" + urlEncode(allocationId) + "/exec?" + query);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(value -> "\"" + jsonEscape(value) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static HttpClient buildWebSocketClient(boolean skipTlsVerify) throws IOException {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        if (!skipTlsVerify) {
            return builder.build();
        }
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
            return builder.build();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to configure insecure TLS WebSocket client", exception);
        }
    }

    private static final class NomadExecListener implements WebSocket.Listener {
        private final CompletableFuture<Integer> exitCode;
        private final OutputStream stdout;
        private final OutputStream stderr;
        private final boolean allowAbnormalCloseSuccess;
        private volatile int closeStatus = -1;
        private volatile String closeReason = "";

        private NomadExecListener(
                CompletableFuture<Integer> exitCode,
                OutputStream stdout,
                OutputStream stderr,
                boolean allowAbnormalCloseSuccess) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.allowAbnormalCloseSuccess = allowAbnormalCloseSuccess;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            LOGGER.info("[nomadContainer] WebSocket opened");
            webSocket.request(1);
        }

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String frame = data == null ? "" : data.toString();
            try {
                handleFrame(frame);
            } catch (Exception exception) {
                exitCode.completeExceptionally(exception);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            try {
                ByteBuffer copy = data == null ? ByteBuffer.allocate(0) : data.asReadOnlyBuffer();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                String frame = new String(bytes, StandardCharsets.UTF_8);
                handleFrame(frame);
            } catch (Exception exception) {
                exitCode.completeExceptionally(exception);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            LOGGER.info("[nomadContainer] WebSocket closed: status=" + statusCode + " reason=" + reason);
            closeStatus = statusCode;
            closeReason = reason == null ? "" : reason;
            if (!exitCode.isDone()) {
                if (statusCode == 1006 && allowAbnormalCloseSuccess) {
                    exitCode.complete(0);
                } else {
                    exitCode.complete(1);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            LOGGER.warning("[nomadContainer] WebSocket error: " + error);
            if (!exitCode.isDone()) {
                exitCode.completeExceptionally(error);
            }
        }

        private void handleFrame(String frame) throws IOException {
            String message = frame == null ? "" : frame;
            LOGGER.fine("[nomadContainer] frame: " + message);
            writeDecoded(message, STDOUT_DATA_PATTERN, stdout);
            writeDecoded(message, STDERR_DATA_PATTERN, stderr);
            if (message.contains("\"exited\":true")) {
                Matcher matcher = EXIT_CODE_PATTERN.matcher(message);
                if (matcher.find()) {
                    int code = Integer.parseInt(matcher.group(1));
                    exitCode.complete(code);
                } else {
                    LOGGER.info("[nomadContainer] received exited=true without parseable exit_code; waiting for close status");
                }
            }
        }

        private boolean isTaskNotStartedYet() {
            return closeStatus == 1008 && closeReason.toLowerCase().contains("not started yet");
        }

        private static void writeDecoded(String frame, Pattern pattern, OutputStream stream) throws IOException {
            Matcher matcher = pattern.matcher(frame);
            while (matcher.find()) {
                byte[] decoded = Base64.getDecoder().decode(matcher.group(1));
                stream.write(decoded);
                stream.flush();
            }
        }
    }

    private static final class NomadExecProc extends Proc {
        private final WebSocket webSocket;
        private final CompletableFuture<Integer> exitCode;

        private NomadExecProc(WebSocket webSocket, CompletableFuture<Integer> exitCode) {
            this.webSocket = webSocket;
            this.exitCode = exitCode;
        }

        @Override
        public boolean isAlive() {
            return !exitCode.isDone();
        }

        @Override
        public void kill() {
            webSocket.abort();
            if (!exitCode.isDone()) {
                exitCode.complete(143);
            }
        }

        @Override
        public int join() throws IOException, InterruptedException {
            try {
                return exitCode.get();
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                throw new IOException("Nomad exec failed: " + cause.getMessage(), cause);
            }
        }

        @Override
        public InputStream getStdout() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getStderr() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream getStdin() {
            return OutputStream.nullOutputStream();
        }
    }
}