package io.jenkins.plugins.nomad;

import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.logging.Logger;

public class NomadLauncher extends JNLPLauncher {
    private static final Logger LOGGER = Logger.getLogger(NomadLauncher.class.getName());

    @Override
    public synchronized void launch(SlaveComputer computer, TaskListener listener) {
        if (!(computer instanceof NomadComputer nomadComputer)) {
            listener.error("Nomad launcher can only launch Nomad agents");
            return;
        }

        NomadAgent agent = nomadComputer.getNode();
        if (agent == null) {
            listener.error("Nomad agent node is not available");
            return;
        }

        NomadCloud cloud = (NomadCloud) jenkins.model.Jenkins.get().getCloud(agent.getCloudName());
        if (cloud == null) {
            listener.error("Nomad cloud '" + agent.getCloudName() + "' no longer exists");
            return;
        }

        String payload = NomadJobBuilder.buildJob(
            cloud,
            agent,
            agent.getTemplate(),
            computer.getJnlpMac(),
            computer.getName());
        listener.getLogger().println("Nomad launcher submitting job for agent " + agent.getNodeName());
        listener.getLogger().println("Rendered Nomad job payload:");
        listener.getLogger().println(payload);
        LOGGER.info(() -> "Rendered Nomad payload for " + agent.getNodeName());

        try {
            NomadApi.SubmitResult result = cloud.connect().submitJob(payload);
            if (result.jobId() != null && !result.jobId().isBlank()) {
                agent.setNomadJobId(result.jobId());
            }
            if (result.evaluationId() != null && !result.evaluationId().isBlank()) {
                agent.setNomadEvaluationId(result.evaluationId());
            }
            listener.getLogger().println("Nomad job submitted successfully");
            if (agent.getNomadJobId() != null) {
                listener.getLogger().println("Nomad job ID: " + agent.getNomadJobId());
            }
            if (agent.getNomadEvaluationId() != null) {
                listener.getLogger().println("Nomad evaluation ID: " + agent.getNomadEvaluationId());
            }
            if (result.jobModifyIndex() != null) {
                listener.getLogger().println("Nomad job modify index: " + result.jobModifyIndex());
            }
            listener.getLogger().println("TODO: poll allocations and wait for the inbound agent to connect");
        } catch (IOException exception) {
            listener.error("Nomad job submission failed: %s", exception.getMessage());
            LOGGER.warning(() -> "Nomad job submission failed for " + agent.getNodeName() + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            listener.error("Nomad job submission interrupted: %s", exception.getMessage());
            LOGGER.warning(() -> "Nomad job submission interrupted for " + agent.getNodeName());
        }
    }
}
