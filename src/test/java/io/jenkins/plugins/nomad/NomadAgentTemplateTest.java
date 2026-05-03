package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class NomadAgentTemplateTest {
    @Test
    public void splitsDatacentersAndMatchesLabels() {
        NomadAgentTemplate template = new NomadAgentTemplate("nomad docker", "jenkins/inbound-agent:jdk21");
        template.setDatacenters("dc1, dc2");

        List<String> datacenters = template.getDatacenterList();

        assertEquals(List.of("dc1", "dc2"), datacenters);
        assertTrue(template.matchesLabel("nomad"));
        assertTrue(template.matchesLabel("nomad docker"));
        assertFalse(template.matchesLabel("linux"));
    }
}
