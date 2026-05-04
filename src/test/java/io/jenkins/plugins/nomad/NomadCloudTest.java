package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.sun.net.httpserver.HttpServer;
import hudson.util.FormValidation;
import hudson.model.Label;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class NomadCloudTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void canProvisionMatchingLabel() throws Exception {
        NomadPipelineContext.clearTemplateScopesForTests();
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setTemplates(java.util.List.of(new NomadAgentTemplate("nomad docker", "jenkins/inbound-agent:jdk21")));

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get("nomad"), 1)));
        assertFalse(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get("windows"), 1)));
    }

    @Test
    public void testConnectionReturnsOkOnHealthyNomadEndpoint() throws Exception {
        NomadPipelineContext.clearTemplateScopesForTests();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/status/leader", exchange -> {
            byte[] body = "\"127.0.0.1:4647\"".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            NomadCloud.DescriptorImpl descriptor = jenkinsRule.jenkins.getDescriptorByType(NomadCloud.DescriptorImpl.class);
            FormValidation validation = descriptor.doTestConnection(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "global",
                    "default",
                    NomadCloud.AuthMode.ACL_TOKEN.name(),
                    null,
                    false);
            assertSame(FormValidation.Kind.OK, validation.kind);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testConnectionReturnsErrorOnFailingNomadEndpoint() throws Exception {
        NomadPipelineContext.clearTemplateScopesForTests();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/status/leader", exchange -> {
            byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try {
            NomadCloud.DescriptorImpl descriptor = jenkinsRule.jenkins.getDescriptorByType(NomadCloud.DescriptorImpl.class);
            FormValidation validation = descriptor.doTestConnection(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "global",
                    "default",
                    NomadCloud.AuthMode.ACL_TOKEN.name(),
                    null,
                    false);
            assertSame(FormValidation.Kind.ERROR, validation.kind);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void resolveProvisioningTemplateAppliesScopedContainers() {
        NomadPipelineContext.clearTemplateScopesForTests();

        NomadCloud cloud = new NomadCloud("nomad");
        NomadAgentTemplate baseTemplate = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        baseTemplate.setContainers(java.util.List.of(new NomadContainerTemplate("golang", "golang:1.24")));
        cloud.setTemplates(java.util.List.of(baseTemplate));

        String scopeId = NomadPipelineContext.registerTemplateScope(
                "nomad",
                java.util.List.of(new NomadContainerTemplate("maven", "maven:3.9-eclipse-temurin-21")));

        NomadAgentTemplate resolved = cloud.resolveProvisioningTemplate(Label.get("nomad"));

        assertEquals(2, resolved.getContainers().size());
        assertTrue(resolved.getContainers().stream().anyMatch(c -> "golang".equals(c.getName())));
        assertTrue(resolved.getContainers().stream().anyMatch(c -> "maven".equals(c.getName())));

        NomadPipelineContext.unregisterTemplateScope("nomad", scopeId);
    }

    @Test
    public void resolveProvisioningTemplatePreservesScopedContainerEntrypointAndTty() {
        NomadPipelineContext.clearTemplateScopesForTests();

        NomadCloud cloud = new NomadCloud("nomad");
        NomadAgentTemplate baseTemplate = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        cloud.setTemplates(java.util.List.of(baseTemplate));

        NomadContainerTemplate kaniko = new NomadContainerTemplate("kaniko", "gcr.io/kaniko-project/executor:v1.23.2-debug");
        kaniko.setEntrypoint("/busybox/cat");
        kaniko.setTtyEnabled(true);

        String scopeId = NomadPipelineContext.registerTemplateScope("nomad", java.util.List.of(kaniko));

        NomadAgentTemplate resolved = cloud.resolveProvisioningTemplate(Label.get("nomad"));

        assertEquals(1, resolved.getContainers().size());
        NomadContainerTemplate resolvedKaniko = resolved.getContainers().get(0);
        assertEquals("kaniko", resolvedKaniko.getName());
        assertEquals("/busybox/cat", resolvedKaniko.getEntrypoint());
        assertTrue(resolvedKaniko.isTtyEnabled());

        NomadPipelineContext.unregisterTemplateScope("nomad", scopeId);
    }

    @Test
    public void resolveProvisioningTemplateDoesNotUseScopedContainersFromDifferentLabels() {
        NomadPipelineContext.clearTemplateScopesForTests();

        NomadCloud cloud = new NomadCloud("nomad");
        NomadAgentTemplate baseTemplate = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        cloud.setTemplates(java.util.List.of(baseTemplate));

        String scopeId = NomadPipelineContext.registerTemplateScope(
                "different-label",
                java.util.List.of(new NomadContainerTemplate("maven", "maven:3.9-eclipse-temurin-21")));

        NomadAgentTemplate resolved = cloud.resolveProvisioningTemplate(Label.get("nomad"));

        assertEquals(0, resolved.getContainers().size());

        NomadPipelineContext.unregisterTemplateScope("different-label", scopeId);
    }

    @Test
    public void canProvisionScopedEffectiveLabelUsingBaseTemplate() {
        NomadPipelineContext.clearTemplateScopesForTests();

        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setTemplates(java.util.List.of(new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21")));

        String scopeId = NomadPipelineContext.registerTemplateScope(
                "nomad",
                java.util.List.of(new NomadContainerTemplate("python", "python:3.12")));
        String effectiveLabel = NomadPipelineContext.getEffectiveLabel(scopeId);

        assertTrue(cloud.canProvision(new hudson.slaves.Cloud.CloudState(Label.get(effectiveLabel), 1)));

        NomadAgentTemplate resolved = cloud.resolveProvisioningTemplate(Label.get(effectiveLabel));
        assertEquals(1, resolved.getContainers().size());
        assertEquals("python", resolved.getContainers().get(0).getName());

        NomadPipelineContext.unregisterTemplateScope("nomad", scopeId);
    }
}
