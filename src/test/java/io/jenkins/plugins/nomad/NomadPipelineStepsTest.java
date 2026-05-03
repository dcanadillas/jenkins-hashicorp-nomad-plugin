package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class NomadPipelineStepsTest {
    @Test
    public void nomadTemplateStepHasExpectedDescriptorMetadata() {
        NomadTemplateStep.DescriptorImpl descriptor = new NomadTemplateStep.DescriptorImpl();
        assertEquals("nomadTemplate", descriptor.getFunctionName());
        assertTrue(descriptor.takesImplicitBlockArgument());
    }

    @Test
    public void nomadContainerStepHasExpectedDescriptorMetadata() {
        ContainerStep.DescriptorImpl descriptor = new ContainerStep.DescriptorImpl();
        assertEquals("nomadContainer", descriptor.getFunctionName());
        assertTrue(descriptor.takesImplicitBlockArgument());
    }

    @Test
    public void nomadTemplateStepStoresSidecarContainers() {
        NomadTemplateStep step = new NomadTemplateStep("nomad-tools");
        step.setContainers(List.of(new NomadContainerTemplate("maven", "maven:3.9-eclipse-temurin-21")));

        assertEquals("nomad-tools", step.getLabel());
        assertEquals(1, step.getContainers().size());
        assertEquals("maven", step.getContainers().get(0).getName());
    }
}
