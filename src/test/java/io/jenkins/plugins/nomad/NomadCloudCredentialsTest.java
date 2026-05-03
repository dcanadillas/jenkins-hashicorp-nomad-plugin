package io.jenkins.plugins.nomad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import java.net.http.HttpRequest;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class NomadCloudCredentialsTest {
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void resolvesAclTokenFromStringCredentials() throws Exception {
        StringCredentialsImpl credential = new StringCredentialsImpl(
                CredentialsScope.GLOBAL,
                "nomad-token-id",
                "Nomad token",
                hudson.util.Secret.fromString("token-from-credential"));

        CredentialsProvider.lookupStores(jenkinsRule.jenkins).iterator().next().addCredentials(Domain.global(), credential);

        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setAuthMode(NomadCloud.AuthMode.ACL_TOKEN);
        cloud.setAclTokenCredentialsId("nomad-token-id");

        NomadApi api = cloud.connect();
        HttpRequest request = api.buildSubmitJobRequest("{\"Job\":{}}");

        assertEquals("token-from-credential", request.headers().firstValue("X-Nomad-Token").orElse(null));
    }

    @Test
    public void doesNotSendTokenHeaderInWorkloadIdentityMode() throws Exception {
        NomadCloud cloud = new NomadCloud("nomad");
        cloud.setAuthMode(NomadCloud.AuthMode.WORKLOAD_IDENTITY);
        cloud.setAclTokenCredentialsId("nomad-token-id");
        cloud.setSkipTlsVerify(true);

        NomadApi api = cloud.connect();
        HttpRequest request = api.buildSubmitJobRequest("{\"Job\":{}}");

        assertEquals(null, request.headers().firstValue("X-Nomad-Token").orElse(null));
        assertTrue(api.isSkipTlsVerify());
    }
}
