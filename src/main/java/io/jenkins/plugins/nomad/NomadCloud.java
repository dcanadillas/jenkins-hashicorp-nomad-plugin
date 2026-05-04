package io.jenkins.plugins.nomad;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class NomadCloud extends Cloud {
    public enum AuthMode {
        ACL_TOKEN,
        WORKLOAD_IDENTITY
    }

    private String nomadAddress = "http://127.0.0.1:4646";
    private String region = "global";
    private String namespace = "default";
    private String datacenters = "dc1";
    private String jenkinsUrl;
    private boolean useWebSocket = true;
    private AuthMode authMode = AuthMode.ACL_TOKEN;
    private String aclTokenCredentialsId;
    private String workloadIdentityAudience;
    private boolean skipTlsVerify;
    private List<NomadAgentTemplate> templates = new ArrayList<>();

    @DataBoundConstructor
    public NomadCloud(String name) {
        super(name);
    }

    public String getNomadAddress() {
        return nomadAddress;
    }

    @DataBoundSetter
    public void setNomadAddress(String nomadAddress) {
        this.nomadAddress = Util.fixEmptyAndTrim(nomadAddress);
    }

    public String getRegion() {
        return region;
    }

    @DataBoundSetter
    public void setRegion(String region) {
        this.region = Util.fixEmptyAndTrim(region);
    }

    public String getNamespace() {
        return namespace;
    }

    @DataBoundSetter
    public void setNamespace(String namespace) {
        this.namespace = Util.fixEmptyAndTrim(namespace);
    }

    public String getDatacenters() {
        return datacenters;
    }

    @DataBoundSetter
    public void setDatacenters(String datacenters) {
        this.datacenters = Util.fixEmptyAndTrim(datacenters);
    }

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    @DataBoundSetter
    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = Util.fixEmptyAndTrim(jenkinsUrl);
    }

    public boolean isUseWebSocket() {
        return useWebSocket;
    }

    @DataBoundSetter
    public void setUseWebSocket(boolean useWebSocket) {
        this.useWebSocket = useWebSocket;
    }

    @NonNull
    public AuthMode getAuthMode() {
        return authMode == null ? AuthMode.ACL_TOKEN : authMode;
    }

    @DataBoundSetter
    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode == null ? AuthMode.ACL_TOKEN : authMode;
    }

    public String getAclTokenCredentialsId() {
        return aclTokenCredentialsId;
    }

    @DataBoundSetter
    public void setAclTokenCredentialsId(String aclTokenCredentialsId) {
        this.aclTokenCredentialsId = Util.fixEmptyAndTrim(aclTokenCredentialsId);
    }

    public String getWorkloadIdentityAudience() {
        return workloadIdentityAudience;
    }

    @DataBoundSetter
    public void setWorkloadIdentityAudience(String workloadIdentityAudience) {
        this.workloadIdentityAudience = Util.fixEmptyAndTrim(workloadIdentityAudience);
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    @DataBoundSetter
    public void setSkipTlsVerify(boolean skipTlsVerify) {
        this.skipTlsVerify = skipTlsVerify;
    }

    @NonNull
    public List<NomadAgentTemplate> getTemplates() {
        return templates == null ? Collections.emptyList() : templates;
    }

    @DataBoundSetter
    public void setTemplates(List<NomadAgentTemplate> templates) {
        this.templates = templates == null ? new ArrayList<>() : new ArrayList<>(templates);
    }

    @Override
    public boolean canProvision(@NonNull CloudState state) {
        Label label = state.getLabel();
        if (label == null) {
            return !getTemplates().isEmpty();
        }
        return getTemplates().stream().anyMatch(template -> template.matchesLabel(label.getExpression()));
    }

    @NonNull
    @Override
    public Collection<PlannedNode> provision(@NonNull CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        NomadAgentTemplate template = resolveProvisioningTemplate(label);
        if (template == null) {
            return Collections.emptyList();
        }

        List<PlannedNode> plannedNodes = new ArrayList<>();
        int plannedCount = Math.max(1, excessWorkload);
        for (int index = 0; index < plannedCount; index++) {
            String agentName = "nomad-" + UUID.randomUUID().toString().substring(0, 12);
            CompletableFuture<Node> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return new NomadAgent(agentName, this, template, label == null ? template.getLabel() : label.getExpression());
                } catch (IOException | Descriptor.FormException exception) {
                    throw new IllegalStateException("Failed to create Nomad agent", exception);
                }
            });
            plannedNodes.add(new NodeProvisioner.PlannedNode(agentName, future, 1));
        }
        return plannedNodes;
    }

    public NomadAgentTemplate chooseTemplate(Label label) {
        if (getTemplates().isEmpty()) {
            return null;
        }
        if (label == null) {
            return getTemplates().get(0);
        }
        return getTemplates().stream()
                .filter(template -> template.matchesLabel(label.getExpression()))
                .findFirst()
                .orElse(null);
    }

    NomadAgentTemplate resolveProvisioningTemplate(Label label) {
        NomadAgentTemplate base = chooseTemplate(label);
        if (base == null) {
            return null;
        }

        String labelExpression = label == null ? base.getLabel() : label.getExpression();
        List<NomadContainerTemplate> scopedContainers = NomadPipelineContext.getCurrentContainersForLabel(labelExpression);
        if (scopedContainers.isEmpty()) {
            return base;
        }

        List<NomadContainerTemplate> mergedContainers = mergeContainers(base.getContainers(), scopedContainers);
        return copyTemplate(base, mergedContainers);
    }

    static List<NomadContainerTemplate> mergeContainers(
            List<NomadContainerTemplate> base,
            List<NomadContainerTemplate> overrides) {
        Map<String, NomadContainerTemplate> byName = new LinkedHashMap<>();
        addContainers(byName, base);
        addContainers(byName, overrides);
        return new ArrayList<>(byName.values());
    }

    private static void addContainers(Map<String, NomadContainerTemplate> target, List<NomadContainerTemplate> source) {
        if (source == null) {
            return;
        }
        for (NomadContainerTemplate container : source) {
            if (container == null || container.getName() == null || container.getName().isBlank()) {
                continue;
            }
            target.put(container.getName().trim(), copyContainer(container));
        }
    }

    private static NomadAgentTemplate copyTemplate(NomadAgentTemplate base, List<NomadContainerTemplate> containers) {
        NomadAgentTemplate copied = new NomadAgentTemplate(base.getLabel(), base.getImage());
        copied.setDatacenters(base.getDatacenters());
        copied.setNamespace(base.getNamespace());
        copied.setCommand(base.getCommand());
        copied.setArgs(base.getArgs());
        copied.setWorkspaceDir(base.getWorkspaceDir());
        copied.setCpu(base.getCpu());
        copied.setMemoryMb(base.getMemoryMb());
        copied.setInstanceCap(base.getInstanceCap());
        copied.setContainers(containers == null ? List.of() : containers);
        return copied;
    }

    private static NomadContainerTemplate copyContainer(NomadContainerTemplate source) {
        NomadContainerTemplate copied = new NomadContainerTemplate(source.getName(), source.getImage());
        copied.setEntrypoint(source.getEntrypoint());
        copied.setCommand(source.getCommand());
        copied.setArgs(source.getArgs());
        copied.setCpu(source.getCpu());
        copied.setMemoryMb(source.getMemoryMb());
        copied.setTtyEnabled(source.isTtyEnabled());
        return copied;
    }

    public String resolveJenkinsUrl() {
        if (jenkinsUrl != null && !jenkinsUrl.isBlank()) {
            return jenkinsUrl;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        return jenkins == null ? "http://127.0.0.1:8080/" : jenkins.getRootUrl();
    }

    public NomadApi connect() {
        String token = resolveAclToken();
        return new NomadApi(nomadAddress, region, namespace, token, skipTlsVerify);
    }

    public boolean isWorkloadIdentityMode() {
        return getAuthMode() == AuthMode.WORKLOAD_IDENTITY;
    }

    String resolveAclToken() {
        return resolveAclToken(getAuthMode(), aclTokenCredentialsId);
    }

    static AuthMode parseAuthMode(String authMode) {
        try {
            return authMode == null ? AuthMode.ACL_TOKEN : AuthMode.valueOf(authMode);
        } catch (IllegalArgumentException exception) {
            return AuthMode.ACL_TOKEN;
        }
    }

    static String resolveAclToken(AuthMode authMode, String aclTokenCredentialsId) {
        if (authMode != AuthMode.ACL_TOKEN || aclTokenCredentialsId == null || aclTokenCredentialsId.isBlank()) {
            return null;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        List<StringCredentials> credentials = CredentialsProvider.lookupCredentials(StringCredentials.class, jenkins, ACL.SYSTEM, Collections.emptyList());
        StringCredentials matching = CredentialsMatchers.firstOrNull(
                credentials,
                CredentialsMatchers.withId(aclTokenCredentialsId));
        return matching == null ? null : matching.getSecret().getPlainText();
    }

    @Symbol("nomad")
    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Nomad";
        }

        public FormValidation doCheckNomadAddress(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Nomad address is required");
            }
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                return FormValidation.warning("Use a full Nomad URL such as http://127.0.0.1:4646");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckJenkinsUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.warning("Jenkins root URL will fall back to the controller root URL");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillAuthModeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("ACL Token", AuthMode.ACL_TOKEN.name());
            items.add("Workload Identity", AuthMode.WORKLOAD_IDENTITY.name());
            return items;
        }

        public ListBoxModel doFillAclTokenCredentialsIdItems(@QueryParameter String aclTokenCredentialsId) {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return new StandardListBoxModel().includeCurrentValue(aclTokenCredentialsId);
            }
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(aclTokenCredentialsId);
            }
            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            jenkins,
                            StringCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(aclTokenCredentialsId);
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String nomadAddress,
                @QueryParameter String region,
                @QueryParameter String namespace,
                @QueryParameter String authMode,
                @QueryParameter String aclTokenCredentialsId,
                @QueryParameter boolean skipTlsVerify) {
            Jenkins jenkins = Jenkins.getInstanceOrNull();
            if (jenkins == null) {
                return FormValidation.error("Jenkins instance is unavailable");
            }
            jenkins.checkPermission(Jenkins.ADMINISTER);

            if (nomadAddress == null || nomadAddress.isBlank()) {
                return FormValidation.error("Nomad address is required");
            }
            if (!nomadAddress.startsWith("http://") && !nomadAddress.startsWith("https://")) {
                return FormValidation.error("Nomad address must start with http:// or https://");
            }

            AuthMode selectedAuthMode = parseAuthMode(authMode);
            String token = resolveAclToken(selectedAuthMode, Util.fixEmptyAndTrim(aclTokenCredentialsId));
            NomadApi api = new NomadApi(
                    Util.fixEmptyAndTrim(nomadAddress),
                    Util.fixEmptyAndTrim(region),
                    Util.fixEmptyAndTrim(namespace),
                    token,
                    skipTlsVerify);
            try {
                String leader = api.ping();
                return FormValidation.ok("Connected to Nomad successfully. Leader: " + leader);
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return FormValidation.error(exception, "Failed to connect to Nomad");
            }
        }
    }
}
