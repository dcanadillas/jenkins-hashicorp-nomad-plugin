package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

public class NomadContainerExecDecoratorTest {
    @Test
    public void durableRunnerCreatesDirectoryAndRecoversScriptCopy() throws Exception {
        Method method = NomadContainerExecDecorator.class.getDeclaredMethod(
                "buildDetachedDurableRunnerCommand",
                List.class,
                String.class,
                String.class);
        method.setAccessible(true);

        String durableDir = "/tmp/jenkins-agent/workspace/Nomad Tests/Nomad-HCL-Template@tmp/durable-9eee66b5";
        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) method.invoke(
                null,
                List.of("/bin/sh", "-xe", durableDir + "/script.sh"),
                durableDir,
                "python");

        assertEquals(List.of("/bin/sh", "-c"), command.subList(0, 2));
        assertTrue(command.get(2).contains("mkdir -p '" + durableDir + "'"));
        assertTrue(command.get(2).contains("cp '" + durableDir + "/script.sh' '" + durableDir + "/script.sh.copy'"));
        assertTrue(command.get(2).contains("durable script unavailable"));
        assertTrue(command.get(2).contains("sh -xe '" + durableDir + "/script.sh.copy' > '" + durableDir + "/jenkins-log.txt' 2>&1"));
    }

    @Test
    public void workspaceExportFallsBackToPwdWhenDerivedPathMissing() throws Exception {
        Method method = NomadContainerExecDecorator.class.getDeclaredMethod(
                "buildExportPrefix",
                String[].class,
                String.class,
                String.class);
        method.setAccessible(true);

        String prefix = (String) method.invoke(
                null,
                new String[] {"REGISTRY=registry.gitlab.com", "WORKSPACE=/tmp/jenkins-agent/workspace/demoapp-dc"},
                "/tmp/jenkins-agent/workspace/demoapp-dc",
                "/tmp/jenkins-agent/workspace/demoapp-dc");

        assertTrue(prefix.contains("if [ -d '/tmp/jenkins-agent/workspace/demoapp-dc' ]; then export WORKSPACE='/tmp/jenkins-agent/workspace/demoapp-dc'; else export WORKSPACE=\"$(pwd)\"; fi;"));
        assertTrue(prefix.contains("export WORKSPACE_TMP=\"${WORKSPACE}@tmp\";"));
        assertTrue(prefix.contains("export REGISTRY='registry.gitlab.com';"));
    }

    @Test
    public void exportedEnvironmentChangesToWorkingDirectoryWhenAvailable() throws Exception {
        Method method = NomadContainerExecDecorator.class.getDeclaredMethod(
                "withExportedEnvironment",
                List.class,
                String[].class,
                String.class,
                String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> wrapped = (List<String>) method.invoke(
                null,
                List.of("/bin/sh", "-c", "pwd"),
                new String[] {"REGISTRY=registry.gitlab.com"},
                "/tmp/jenkins-agent/workspace/demoapp-dc",
                "/tmp/jenkins-agent/workspace/demoapp-dc");

        assertEquals(List.of("/bin/sh", "-lc"), wrapped.subList(0, 2));
                assertTrue(wrapped.get(2).contains("mkdir -p '/tmp/jenkins-agent/workspace/demoapp-dc'; cd '/tmp/jenkins-agent/workspace/demoapp-dc';"));
        assertTrue(wrapped.get(2).contains("if [ -d '/tmp/jenkins-agent/workspace/demoapp-dc' ]; then export WORKSPACE='/tmp/jenkins-agent/workspace/demoapp-dc'; else export WORKSPACE=\"$(pwd)\"; fi;"));
    }
}