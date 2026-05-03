package io.jenkins.plugins.nomad;

import hudson.Util;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class NomadJobBuilder {
    private NomadJobBuilder() {}

        public static String buildJob(
                        NomadCloud cloud,
                        NomadAgent agent,
                        NomadAgentTemplate template,
                        String jnlpSecret,
                        String agentName) {
        String jobId = agent.getNodeName();
        agent.setNomadJobId(jobId);
        List<String> datacenters = template.getDatacenterList().isEmpty()
                ? List.of("dc1")
                : template.getDatacenterList();
        String datacentersJson = datacenters.stream()
                .map(value -> "\"" + escape(value) + "\"")
                .collect(Collectors.joining(", "));

        String rawArgs = Util.fixEmpty(template.getArgs()) == null ? null : template.getArgs();
        String resolvedArgs = rawArgs == null
                ? null
                : rawArgs
                        .replace("${computer.jnlpmac}", jnlpSecret == null ? "" : jnlpSecret)
                        .replace("${computer.name}", agentName == null ? agent.getNodeName() : agentName);

        String args = resolvedArgs == null
                ? "[]"
                : "[" + Arrays.stream(resolvedArgs.split("\\s+"))
                        .filter(value -> !value.isBlank())
                        .map(value -> "\"" + escape(value) + "\"")
                        .collect(Collectors.joining(", ")) + "]";

        String commandLine = template.getCommand() == null ? "" : "\n              \"command\": \"" + escape(template.getCommand()) + "\",";
        String namespaceLine = template.getNamespace() == null ? "" : "\n      \"Namespace\": \"" + escape(template.getNamespace()) + "\",";
        String workloadIdentityEnv = cloud.isWorkloadIdentityMode()
                ? ",\n"
                        + "              \"NOMAD_WORKLOAD_IDENTITY\": \"true\",\n"
                        + "              \"NOMAD_WORKLOAD_IDENTITY_AUDIENCE\": \""
                        + escape(cloud.getWorkloadIdentityAudience() == null ? "" : cloud.getWorkloadIdentityAudience())
                        + "\""
                : "";

        String workspaceVolume = "alloc:" + escape(template.getWorkspaceDir());
        String jnlpTask = "          {\n"
                + "            \"Name\": \"jnlp\",\n"
                + "            \"User\": \"0\",\n"
                + "            \"Driver\": \"docker\",\n"
                + "            \"Config\": {\n"
                + "              \"image\": \"" + escape(template.getImage()) + "\"," + commandLine + "\n"
                + "              \"args\": " + args + ",\n"
                + "              \"volumes\": [\"" + workspaceVolume + "\"]\n"
                + "            },\n"
                + "            \"Env\": {\n"
                + "              \"JENKINS_URL\": \"" + escape(cloud.resolveJenkinsUrl()) + "\",\n"
                + "              \"JENKINS_AGENT_NAME\": \"" + escape(agent.getNodeName()) + "\",\n"
                + "              \"JENKINS_SECRET\": \"" + escape(jnlpSecret == null ? "" : jnlpSecret) + "\",\n"
                + "              \"JENKINS_AGENT_WORKDIR\": \"" + escape(template.getWorkspaceDir()) + "\",\n"
                + "              \"JENKINS_WEB_SOCKET\": \"" + cloud.isUseWebSocket() + "\""
                + workloadIdentityEnv + "\n"
                + "            },\n"
                + "            \"Resources\": {\n"
                + "              \"CPU\": " + template.getCpu() + ",\n"
                + "              \"MemoryMB\": " + template.getMemoryMb() + "\n"
                + "            }\n"
                + "          }";

        String sidecarTasks = template.getContainers().stream()
                .filter(container -> container.getName() != null && !container.getName().isBlank())
                .filter(container -> container.getImage() != null && !container.getImage().isBlank())
                .map(container -> {
                    String sidecarCommandValue = container.getCommand();
                    String sidecarArgsValue = container.getArgs();
                    if (Util.fixEmpty(sidecarCommandValue) == null && Util.fixEmpty(sidecarArgsValue) == null) {
                        sidecarCommandValue = "/bin/sh";
                        sidecarArgsValue = "-lc while true; do sleep 30; done";
                    }
                    String sidecarArgs = toArgsJson(sidecarArgsValue);
                    String sidecarCommand = sidecarCommandValue == null
                            ? ""
                            : "\n              \"command\": \"" + escape(sidecarCommandValue) + "\",";
                    String sidecarTtyConfig = container.isTtyEnabled()
                            ? "\n              \"tty\": true,\n              \"interactive\": true,"
                            : "";
                    return "          {\n"
                            + "            \"Name\": \"" + escape(container.getName()) + "\",\n"
                            + "            \"User\": \"0\",\n"
                            + "            \"Driver\": \"docker\",\n"
                            + "            \"Config\": {\n"
                            + "              \"image\": \"" + escape(container.getImage()) + "\"," + sidecarCommand + sidecarTtyConfig + "\n"
                            + "              \"args\": " + sidecarArgs + ",\n"
                            + "              \"volumes\": [\"" + workspaceVolume + "\"]\n"
                            + "            },\n"
                            + "            \"Resources\": {\n"
                            + "              \"CPU\": " + container.getCpu() + ",\n"
                            + "              \"MemoryMB\": " + container.getMemoryMb() + "\n"
                            + "            }\n"
                            + "          }";
                })
                .collect(Collectors.joining(",\n"));

        String tasksJson = sidecarTasks.isEmpty() ? jnlpTask : jnlpTask + ",\n" + sidecarTasks;

        return "{\n"
                + "  \"Job\": {\n"
                + "    \"ID\": \"" + escape(jobId) + "\",\n"
                + "    \"Name\": \"" + escape(jobId) + "\",\n"
                + "    \"Type\": \"batch\",\n"
                + "    \"Datacenters\": [" + datacentersJson + "],\n"
                + "    \"Region\": \"" + escape(cloud.getRegion()) + "\"," + namespaceLine + "\n"
                + "    \"TaskGroups\": [\n"
                + "      {\n"
                + "        \"Name\": \"agent\",\n"
                + "        \"Count\": 1,\n"
                + "        \"Tasks\": [\n"
                                + tasksJson + "\n"
                + "        ]\n"
                + "      }\n"
                + "    ]\n"
                + "  }\n"
                + "}";
    }

        private static String toArgsJson(String args) {
                if (Util.fixEmpty(args) == null) {
                        return "[]";
                }
                return "[" + Arrays.stream(args.split("\\s+"))
                                .filter(value -> !value.isBlank())
                                .map(value -> "\"" + escape(value) + "\"")
                                .collect(Collectors.joining(", ")) + "]";
        }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
