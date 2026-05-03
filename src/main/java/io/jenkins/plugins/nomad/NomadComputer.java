package io.jenkins.plugins.nomad;

import hudson.slaves.AbstractCloudComputer;

public class NomadComputer extends AbstractCloudComputer<NomadAgent> {
    public NomadComputer(NomadAgent agent) {
        super(agent);
    }
}
