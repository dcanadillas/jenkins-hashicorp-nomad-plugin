package io.jenkins.plugins.nomad;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.io.Serializable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class NomadContainerTemplate extends AbstractDescribableImpl<NomadContainerTemplate> implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String image;
    private String command;
    private String args;
    private int cpu = 500;
    private int memoryMb = 512;
    private boolean ttyEnabled;

    @DataBoundConstructor
    public NomadContainerTemplate(String name, String image) {
        this.name = Util.fixEmptyAndTrim(name);
        this.image = Util.fixEmptyAndTrim(image);
    }

    @CheckForNull
    public String getName() {
        return name;
    }

    @CheckForNull
    public String getImage() {
        return image;
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
        this.memoryMb = Math.max(memoryMb, 128);
    }

    public boolean isTtyEnabled() {
        return ttyEnabled;
    }

    @DataBoundSetter
    public void setTtyEnabled(boolean ttyEnabled) {
        this.ttyEnabled = ttyEnabled;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<NomadContainerTemplate> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Nomad Sidecar Container";
        }
    }
}