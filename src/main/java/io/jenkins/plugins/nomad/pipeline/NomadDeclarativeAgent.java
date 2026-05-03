package io.jenkins.plugins.nomad.pipeline;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import io.jenkins.plugins.nomad.NomadContainerTemplate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.RetryableDeclarativeAgent;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

@SuppressFBWarnings(
        value = "SE_NO_SERIALVERSIONID",
        justification = "Serialization happens via XStream")
public class NomadDeclarativeAgent extends RetryableDeclarativeAgent<NomadDeclarativeAgent> {
    private String label;
    private List<NomadContainerTemplate> containers = new ArrayList<>();
    private String jobHcl;

    @DataBoundConstructor
    public NomadDeclarativeAgent() {}

    public String getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmptyAndTrim(label);
    }

    @NonNull
    public List<NomadContainerTemplate> getContainers() {
        return containers == null ? Collections.emptyList() : containers;
    }

    @DataBoundSetter
    public void setContainers(List<NomadContainerTemplate> containers) {
        this.containers = containers == null ? new ArrayList<>() : new ArrayList<>(containers);
    }

    public String getJobHcl() {
        return jobHcl;
    }

    @DataBoundSetter
    public void setJobHcl(String jobHcl) {
        this.jobHcl = Util.fixEmpty(jobHcl);
    }

    public Map<String, Object> getAsArgs() {
        Map<String, Object> args = new TreeMap<>();
        if (label != null && !label.isBlank()) {
            args.put("label", label);
        }
        if (containers != null && !containers.isEmpty()) {
            args.put("containers", containers);
        }
        if (jobHcl != null && !jobHcl.isBlank()) {
            args.put("jobHcl", jobHcl);
        }
        return args;
    }

    @OptionalExtension(requirePlugins = "pipeline-model-extensions")
    @Symbol("nomad")
    public static class DescriptorImpl extends DeclarativeAgentDescriptor<NomadDeclarativeAgent> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Nomad";
        }
    }
}
