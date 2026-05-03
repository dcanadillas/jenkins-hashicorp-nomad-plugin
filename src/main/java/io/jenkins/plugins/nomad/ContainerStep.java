package io.jenkins.plugins.nomad;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Node;
import hudson.util.FormValidation;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class ContainerStep extends Step {
    private static final Logger LOGGER = Logger.getLogger(ContainerStep.class.getName());
    private final String name;

    @DataBoundConstructor
    public ContainerStep(String name) {
        this.name = name == null ? "" : name.trim();
    }

    public String getName() {
        return name;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    static class Execution extends StepExecution {
        private static final long serialVersionUID = 1L;

        private final transient ContainerStep step;

        Execution(ContainerStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        public boolean start() throws Exception {
            String targetContainer = step.getName();
            LOGGER.info("[nomadContainer] step start requested container='" + targetContainer + "'");
            if (targetContainer == null || targetContainer.isBlank()) {
                throw new AbortException("container step requires a non-empty container name");
            }

            EnvVars envVars = getContext().get(EnvVars.class);
            Node node = getContext().get(Node.class);
            Set<String> available = resolveAvailableContainers(envVars, node);
            LOGGER.info("[nomadContainer] available containers=" + available);
            if (!available.contains(targetContainer)) {
                throw new AbortException(
                        "container '" + targetContainer
                                + "' is not available in current context. Available: "
                                + available
                                + ". Define it in nomadTemplate(...) or in the Nomad UI agent template sidecars.");
            }

            EnvironmentExpander base = getContext().get(EnvironmentExpander.class);
            EnvironmentExpander active = EnvironmentExpander.constant(
                    Collections.singletonMap(NomadPipelineContext.ACTIVE_CONTAINER_ENV, targetContainer));

            getContext()
                    .newBodyInvoker()
                    .withContext(EnvironmentExpander.merge(base, active))
                    .withContext(new NomadContainerExecDecorator(targetContainer))
                    .withCallback(BodyExecutionCallback.wrap(getContext()))
                    .start();
                LOGGER.info("[nomadContainer] decorator attached for container='" + targetContainer + "'");
            return false;
        }

        private static Set<String> resolveAvailableContainers(EnvVars envVars, Node node) {
            Set<String> available = new TreeSet<>();
            available.add("jnlp");

            Set<String> scoped = NomadPipelineContext.decodeContainerNames(
                    envVars == null ? null : envVars.get(NomadPipelineContext.CONTAINER_NAMES_ENV));
            if (!scoped.isEmpty()) {
                available.addAll(scoped);
                return available;
            }

            if (node instanceof NomadAgent nomadAgent && nomadAgent.getTemplate() != null) {
                for (NomadContainerTemplate container : nomadAgent.getTemplate().getContainers()) {
                    if (container == null || container.getName() == null || container.getName().isBlank()) {
                        continue;
                    }
                    available.add(container.getName().trim());
                }
            }
            return available;
        }
    }

    @Symbol("nomadContainer")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "nomadContainer";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Run steps in named Nomad container scope";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(EnvVars.class, Node.class);
        }

        public FormValidation doCheckName(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("Container name is required");
            }
            return FormValidation.ok();
        }
    }
}
