package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.Test;

public class NomadPipelineContextTest {
    @Test
    public void encodesAndDecodesContainerNames() {
        String encoded = NomadPipelineContext.encodeContainerNames(Set.of("jnlp", "maven", "go"));
        Set<String> decoded = NomadPipelineContext.decodeContainerNames(encoded);

        assertEquals(Set.of("jnlp", "maven", "go"), decoded);
    }

    @Test
    public void decodesEmptyAsEmptySet() {
        assertTrue(NomadPipelineContext.decodeContainerNames("").isEmpty());
        assertTrue(NomadPipelineContext.decodeContainerNames(null).isEmpty());
    }

    @Test
    public void tracksTemplateScopesByLabel() {
        NomadPipelineContext.clearTemplateScopesForTests();
        NomadContainerTemplate maven = new NomadContainerTemplate("maven", "maven:3.9");

        String scopeId = NomadPipelineContext.registerTemplateScope("nomad", List.of(maven));
        List<NomadContainerTemplate> active = NomadPipelineContext.getCurrentContainersForLabel("nomad");

        assertEquals(1, active.size());
        assertEquals("maven", active.get(0).getName());

        NomadPipelineContext.unregisterTemplateScope("nomad", scopeId);
        assertTrue(NomadPipelineContext.getCurrentContainersForLabel("nomad").isEmpty());
    }

    @Test
    public void returnsMostRecentContainersAcrossLabels() {
        NomadPipelineContext.clearTemplateScopesForTests();
        String scopeA = NomadPipelineContext.registerTemplateScope(
                "nomad",
                List.of(new NomadContainerTemplate("maven", "maven:3.9")));
        String scopeB = NomadPipelineContext.registerTemplateScope(
                "nomad-tools",
                List.of(new NomadContainerTemplate("golang", "golang:1.24")));

        List<NomadContainerTemplate> latest = NomadPipelineContext.getMostRecentContainers();
        assertEquals(1, latest.size());
        assertEquals("golang", latest.get(0).getName());

        NomadPipelineContext.unregisterTemplateScope("nomad", scopeA);
        NomadPipelineContext.unregisterTemplateScope("nomad-tools", scopeB);
    }
}
