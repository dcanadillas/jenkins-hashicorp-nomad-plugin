package io.jenkins.plugins.nomad;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class NomadAgentTemplate extends AbstractDescribableImpl<NomadAgentTemplate> implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String label;
    private final String image;
    private String datacenters = "dc1";
    private String namespace;
    private String command;
    private String args = "${computer.jnlpmac} ${computer.name}";
    private String workspaceDir = "/tmp/jenkins-agent";
    private int cpu = 1000;
    private int memoryMb = 1024;
    private int instanceCap = Integer.MAX_VALUE;
    private List<NomadContainerTemplate> containers = new ArrayList<>();

    @DataBoundConstructor
    public NomadAgentTemplate(String label, String image) {
        this.label = Util.fixEmptyAndTrim(label);
        this.image = Util.fixEmptyAndTrim(image);
    }

    @CheckForNull
    public String getLabel() {
        return label;
    }

    @CheckForNull
    public String getImage() {
        return image;
    }

    @NonNull
    public List<String> getDatacenterList() {
        if (datacenters == null || datacenters.isBlank()) {
            return Collections.singletonList("dc1");
        }
        return Arrays.stream(datacenters.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toList());
    }

    public String getDatacenters() {
        return datacenters;
    }

    @DataBoundSetter
    public void setDatacenters(String datacenters) {
        this.datacenters = Util.fixEmptyAndTrim(datacenters);
    }

    @CheckForNull
    public String getNamespace() {
        return namespace;
    }

    @DataBoundSetter
    public void setNamespace(String namespace) {
        this.namespace = Util.fixEmptyAndTrim(namespace);
    }

    @CheckForNull
    public String getCommand() {
        return command;
    }

    @DataBoundSetter
    public void setCommand(String command) {
        this.command = Util.fixEmpty(command);
    }

    @CheckForNull
    public String getArgs() {
        return args;
    }

    @DataBoundSetter
    public void setArgs(String args) {
        this.args = Util.fixEmpty(args);
    }

    @NonNull
    public String getWorkspaceDir() {
        return workspaceDir == null || workspaceDir.isBlank() ? "/tmp/jenkins-agent" : workspaceDir;
    }

    @DataBoundSetter
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = Util.fixEmptyAndTrim(workspaceDir);
    }

    public int getCpu() {
        return cpu;
    }

    @DataBoundSetter
    public void setCpu(int cpu) {
        this.cpu = Math.max(cpu, 100);
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    @DataBoundSetter
    public void setMemoryMb(int memoryMb) {
        this.memoryMb = Math.max(memoryMb, 256);
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = instanceCap < 0 ? Integer.MAX_VALUE : instanceCap;
    }

    @NonNull
    public List<NomadContainerTemplate> getContainers() {
        return containers == null ? Collections.emptyList() : containers;
    }

    @DataBoundSetter
    public void setContainers(List<NomadContainerTemplate> containers) {
        this.containers = containers == null ? new ArrayList<>() : new ArrayList<>(containers);
    }

    public boolean matchesLabel(@CheckForNull String requestedLabel) {
        if (label == null || requestedLabel == null) {
            return false;
        }
        List<String> available = Arrays.stream(label.split("\\s+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toList());
        List<String> requested = Arrays.stream(requestedLabel.split("\\s+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toList());
        return available.containsAll(requested);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<NomadAgentTemplate> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Nomad Agent Template";
        }
    }
}
