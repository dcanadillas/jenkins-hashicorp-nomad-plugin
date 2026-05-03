package io.jenkins.plugins.nomad;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class NomadAgent extends AbstractCloudSlave {
    private static final long serialVersionUID = 1L;

    private final String cloudName;
    private final NomadAgentTemplate template;
    private final String labelString;
    private String nomadJobId;
    private String nomadEvaluationId;

    public NomadAgent(String name, NomadCloud cloud, NomadAgentTemplate template, String labelString)
            throws Descriptor.FormException, IOException {
        this(
                name,
                cloud.name,
                template,
                labelString,
                new NomadLauncher(),
                determineRetentionStrategy(template));
    }

    @DataBoundConstructor
    public NomadAgent(
            String name,
            String cloudName,
            NomadAgentTemplate template,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy)
            throws Descriptor.FormException, IOException {
        super(name, template.getWorkspaceDir(), launcher);
        this.cloudName = cloudName;
        this.template = template;
        this.labelString = labelString;
        setNodeDescription("Ephemeral Nomad Jenkins agent");
        setNumExecutors(1);
        setMode(Node.Mode.EXCLUSIVE);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
    }

    private static RetentionStrategy<?> determineRetentionStrategy(NomadAgentTemplate template) {
        return new CloudRetentionStrategy(0);
    }

    public String getCloudName() {
        return cloudName;
    }

    public NomadAgentTemplate getTemplate() {
        return template;
    }

    public String getAgentLabelString() {
        return labelString;
    }

    public String getNomadJobId() {
        return nomadJobId;
    }

    public void setNomadJobId(String nomadJobId) {
        this.nomadJobId = nomadJobId;
    }

    public String getNomadEvaluationId() {
        return nomadEvaluationId;
    }

    public void setNomadEvaluationId(String nomadEvaluationId) {
        this.nomadEvaluationId = nomadEvaluationId;
    }

    @Override
    public AbstractCloudComputer<NomadAgent> createComputer() {
        return new NomadComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("Terminating Nomad agent " + getNodeName());
        if (nomadJobId != null) {
            NomadCloud cloud = (NomadCloud) Jenkins.get().getCloud(cloudName);
            if (cloud == null) {
                listener.error("Cannot stop Nomad job %s because cloud '%s' no longer exists", nomadJobId, cloudName);
            } else {
                try {
                    cloud.connect().stopJob(nomadJobId, true);
                    listener.getLogger().println("Stopped and purged Nomad job " + nomadJobId);
                    nomadJobId = null;
                } catch (IOException exception) {
                    listener.error("Failed to stop/purge Nomad job %s: %s", nomadJobId, exception.getMessage());
                }
            }
        }
        if (nomadEvaluationId != null) {
            listener.getLogger().println("Last Nomad evaluation: " + nomadEvaluationId);
        }
    }
}
