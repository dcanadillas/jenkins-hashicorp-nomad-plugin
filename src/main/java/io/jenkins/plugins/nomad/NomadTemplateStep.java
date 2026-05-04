package io.jenkins.plugins.nomad;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class NomadTemplateStep extends Step {
    private final String label;
    private List<NomadContainerTemplate> containers = new ArrayList<>();
    private String jobHcl;

    @DataBoundConstructor
    public NomadTemplateStep(String label) {
        this.label = label == null || label.isBlank() ? "nomad" : label.trim();
    }

    public String getLabel() {
        return label;
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
        this.jobHcl = jobHcl;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(this, context);
    }

    static class Execution extends StepExecution {
        private static final long serialVersionUID = 1L;

        private final transient NomadTemplateStep step;

        Execution(NomadTemplateStep step, StepContext context) {
            super(context);
            this.step = step;
        }

        @Override
        public boolean start() throws Exception {
            String scopeLabel = step.getLabel();
            List<NomadContainerTemplate> scopedContainers = step.getContainers();
            if ((scopedContainers == null || scopedContainers.isEmpty())
                    && step.getJobHcl() != null
                    && !step.getJobHcl().isBlank()) {
                scopedContainers = NomadHclParser.parseContainersFromJobHcl(step.getJobHcl());
            }

            String scopeId = NomadPipelineContext.registerTemplateScope(scopeLabel, scopedContainers);
            String effectiveLabel = NomadPipelineContext.getEffectiveLabel(scopeId);

            Set<String> names = new HashSet<>();
            names.add("jnlp");
            for (NomadContainerTemplate container : scopedContainers) {
                if (container.getName() != null && !container.getName().isBlank()) {
                    names.add(container.getName().trim());
                }
            }

            EnvironmentExpander base = getContext().get(EnvironmentExpander.class);
            EnvironmentExpander scoped = EnvironmentExpander.constant(
                    Collections.singletonMap(NomadPipelineContext.TEMPLATE_LABEL_ENV, effectiveLabel));
                EnvironmentExpander scopedBase = EnvironmentExpander.constant(
                    Collections.singletonMap(NomadPipelineContext.TEMPLATE_BASE_LABEL_ENV, scopeLabel));
            EnvironmentExpander containers = EnvironmentExpander.constant(
                    Collections.singletonMap(
                            NomadPipelineContext.CONTAINER_NAMES_ENV,
                            NomadPipelineContext.encodeContainerNames(names)));

            getContext()
                    .newBodyInvoker()
                    .withContext(EnvironmentExpander.merge(base, EnvironmentExpander.merge(scopedBase, EnvironmentExpander.merge(scoped, containers))))
                    .withCallback(new ScopedContainersCleanupCallback(scopeLabel, scopeId))
                    .start();
            return false;
        }
    }

    private static final class ScopedContainersCleanupCallback extends BodyExecutionCallback.TailCall {
        private static final long serialVersionUID = 1L;

        private final String label;
        private final String scopeId;

        private ScopedContainersCleanupCallback(String label, String scopeId) {
            this.label = label;
            this.scopeId = scopeId;
        }

        @Override
        protected void finished(StepContext context) {
            NomadPipelineContext.unregisterTemplateScope(label, scopeId);
        }
    }

    @Symbol("nomadTemplate")
    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "nomadTemplate";
        }

        @NonNull
        @Override
        public String getDisplayName() {
            return "Define Nomad template scope";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(EnvVars.class);
        }
    }
}
