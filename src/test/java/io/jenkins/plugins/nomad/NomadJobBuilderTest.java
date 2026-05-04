package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class NomadJobBuilderTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void rendersJobPayloadWithTemplateValues() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        template.setDatacenters("dc1,dc2");
        template.setCpu(1500);
        template.setMemoryMb(2048);
        template.setArgs("${computer.jnlpmac} ${computer.name}");

        NomadAgent agent = new NomadAgent("nomad-agent-1", cloud, template, "nomad");

        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-123", "nomad-agent-1");

        assertTrue(payload.contains("\"ID\": \"nomad-agent-1\""));
        assertTrue(payload.contains("\"image\": \"jenkins/inbound-agent:jdk21\""));
        assertTrue(payload.contains("\"JENKINS_URL\": \"http://jenkins.example/\""));
        assertTrue(payload.contains("\"JENKINS_AGENT_NAME\": \"nomad-agent-1\""));
        assertTrue(payload.contains("\"JENKINS_SECRET\": \"secret-123\""));
        assertTrue(payload.contains("\"JENKINS_AGENT_WORKDIR\": \"/tmp/jenkins-agent\""));
        assertTrue(payload.contains("\"args\": [\"secret-123\", \"nomad-agent-1\", \"-workDir\", \"/tmp/jenkins-agent\"]"));
        assertTrue(payload.contains("\"volumes\": [\"${NOMAD_ALLOC_DIR}:/tmp/jenkins-agent\"]"));
        assertTrue(payload.contains("\"Name\": \"jnlp\""));
        assertTrue(payload.contains("\"User\": \"0\""));
        assertTrue(payload.contains("\"CPU\": 1500"));
        assertTrue(payload.contains("\"MemoryMB\": 2048"));
    }

    @Test
    public void addsWorkloadIdentityEnvironmentWhenConfigured() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setAuthMode(NomadCloud.AuthMode.WORKLOAD_IDENTITY);
        cloud.setWorkloadIdentityAudience("jenkins-nomad-agents");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        NomadAgent agent = new NomadAgent("nomad-agent-wi", cloud, template, "nomad");

        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-xyz", "nomad-agent-wi");

        assertTrue(payload.contains("\"NOMAD_WORKLOAD_IDENTITY\": \"true\""));
        assertTrue(payload.contains("\"NOMAD_WORKLOAD_IDENTITY_AUDIENCE\": \"jenkins-nomad-agents\""));
        assertTrue(payload.contains("\"args\": [\"secret-xyz\", \"nomad-agent-wi\", \"-workDir\", \"/tmp/jenkins-agent\"]"));
    }

    @Test
    public void preservesExistingWorkDirArgumentInArgs() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        template.setArgs("${computer.jnlpmac} ${computer.name} -workDir /home/jenkins/agent");
        NomadAgent agent = new NomadAgent("nomad-agent-workdir", cloud, template, "nomad");

        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-456", "nomad-agent-workdir");

        assertTrue(payload.contains("\"args\": [\"secret-456\", \"nomad-agent-workdir\", \"-workDir\", \"/home/jenkins/agent\"]"));
        assertTrue(!payload.contains("\"/tmp/jenkins-agent\", \"-workDir\""));
    }

    @Test
    public void rendersSidecarTasksWhenConfigured() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        NomadContainerTemplate maven = new NomadContainerTemplate("maven", "maven:3.9-eclipse-temurin-21");
        maven.setArgs("sleep infinity");
        maven.setCpu(700);
        maven.setMemoryMb(1024);
        template.setContainers(List.of(maven));

        NomadAgent agent = new NomadAgent("nomad-agent-sidecar", cloud, template, "nomad");

        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-abc", "nomad-agent-sidecar");

        assertTrue(payload.contains("\"Name\": \"jnlp\""));
        assertTrue(payload.contains("\"Name\": \"maven\""));
        assertTrue(payload.contains("\"image\": \"maven:3.9-eclipse-temurin-21\""));
        assertTrue(payload.contains("\"Name\": \"maven\",\n            \"User\": \"0\""));
        assertTrue(payload.contains("\"args\": [\"sleep\", \"infinity\"]"));
        assertTrue(payload.contains("\"CPU\": 700"));
        assertTrue(payload.contains("\"MemoryMB\": 1024"));
    }

    @Test
    public void ignoresInvalidSidecarRows() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        NomadContainerTemplate invalidNoName = new NomadContainerTemplate("", "maven:3.9-eclipse-temurin-21");
        NomadContainerTemplate invalidNoImage = new NomadContainerTemplate("tools", "");
        NomadContainerTemplate valid = new NomadContainerTemplate("golang", "golang:1.24");
        template.setContainers(List.of(invalidNoName, invalidNoImage, valid));

        NomadAgent agent = new NomadAgent("nomad-agent-filtered", cloud, template, "nomad");
        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-abc", "nomad-agent-filtered");

        assertTrue(payload.contains("\"Name\": \"jnlp\""));
        assertTrue(payload.contains("\"Name\": \"golang\""));
        assertTrue(payload.contains("\"image\": \"golang:1.24\""));
        assertTrue(!payload.contains("\"Name\": \"tools\""));
        assertTrue(!payload.contains("\"image\": \"maven:3.9-eclipse-temurin-21\""));
    }

    @Test
    public void appliesKeepAliveDefaultsForSidecarWithoutCommandOrArgs() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        NomadContainerTemplate maven = new NomadContainerTemplate("maven", "maven:3.9-eclipse-temurin-21");
        template.setContainers(List.of(maven));

        NomadAgent agent = new NomadAgent("nomad-agent-default-sidecar", cloud, template, "nomad");
        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-abc", "nomad-agent-default-sidecar");

        assertTrue(payload.contains("\"Name\": \"maven\""));
        assertTrue(payload.contains("\"command\": \"/bin/sh\""));
        assertTrue(payload.contains("\"args\": [\"-lc\", \"while\", \"true;\", \"do\", \"sleep\", \"30;\", \"done\"]"));
    }

    @Test
    public void rendersTtyAndInteractiveForSidecarWhenEnabled() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setJenkinsUrl("http://jenkins.example/");

        NomadAgentTemplate template = new NomadAgentTemplate("nomad", "jenkins/inbound-agent:jdk21");
        NomadContainerTemplate kaniko = new NomadContainerTemplate("kaniko", "gcr.io/kaniko-project/executor:debug");
        kaniko.setEntrypoint("/busybox/cat");
        kaniko.setTtyEnabled(true);
        template.setContainers(List.of(kaniko));

        NomadAgent agent = new NomadAgent("nomad-agent-tty", cloud, template, "nomad");
        String payload = NomadJobBuilder.buildJob(cloud, agent, template, "secret-abc", "nomad-agent-tty");

        assertTrue(payload.contains("\"Name\": \"kaniko\""));
        assertTrue(payload.contains("\"entrypoint\": [\"/busybox/cat\"]"));
        assertTrue(payload.contains("\"tty\": true"));
        assertTrue(payload.contains("\"interactive\": true"));
        assertTrue(!payload.contains("\"command\": \"/bin/sh\""));
    }
}
