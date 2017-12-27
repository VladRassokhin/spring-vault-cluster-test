package vault;

import org.jetbrains.annotations.NotNull;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.config.ClientHttpRequestFactoryFactory;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.*;
import org.testcontainers.containers.DockerComposeContainer;

import java.io.File;
import java.net.URI;
import java.util.Collections;

import static org.assertj.core.api.BDDAssertions.then;

@SuppressWarnings("StaticVariableMayNotBeInitialized")
public class VaultClusterSimpleTest {
    @ClassRule
    public static DockerComposeContainer environment =
            new DockerComposeContainer(new File("src/test/resources/docker-compose.yaml"))
                    .withLocalCompose(true);

    private static VaultEndpoint ourEndpoint1;
    private static VaultEndpoint ourEndpoint2;

    private static String ourUnsealKey;
    private static String ourRootToken;

    @BeforeClass
    public static void setUpVault() throws InterruptedException {
        URI vault1URI = URI.create("http://127.0.0.1:8300");
        URI vault2URI = URI.create("http://127.0.0.1:8400");

        ourEndpoint1 = VaultEndpoint.from(vault1URI);
        ourEndpoint2 = VaultEndpoint.from(vault2URI);

        final ClientHttpRequestFactory factory = createFactory();
        final VaultTemplate template1 = new VaultTemplate(ourEndpoint1, factory, () -> VaultToken.of(""));
        final VaultTemplate template2 = new VaultTemplate(ourEndpoint2, factory, () -> VaultToken.of(""));

        // Initialize master
        {
            final VaultInitializationResponse initialize = template1.opsForSys().initialize(VaultInitializationRequest.create(1, 1));
            then(initialize.getKeys()).hasSize(1);
            ourUnsealKey = initialize.getKeys().iterator().next();
            ourRootToken = initialize.getRootToken().getToken();
        }

        // Unseal both
        then(template1.opsForSys().unseal(ourUnsealKey).isSealed()).isFalse();
        doCheckHealth(template1);

        // Wait for some time so first node would aquire lock
        Thread.sleep(1000);

        then(template2.opsForSys().unseal(ourUnsealKey).isSealed()).isFalse();
        doCheckHealth(template2);

        assertModes(template1, template2);

        // Configure using first
        final VaultTemplate template1Authorized = new VaultTemplate(ourEndpoint1, factory, () -> VaultToken.of(ourRootToken));
        template1Authorized.write("/secret/test", Collections.singletonMap("data", "simple"));
    }

    private static void assertModes(VaultTemplate template1, VaultTemplate template2) {
        final String mode1 = getMode(template1);
        final String mode2 = getMode(template2);
        System.out.println("Node 1 operates in " + mode1 + " mode");
        System.out.println("Node 2 operates in " + mode2 + " mode");
        then(mode1).isEqualTo("active");
        then(mode2).isEqualTo("standby");
    }

    @NotNull
    private static String getMode(VaultTemplate template) {
        return template.opsForSys().health().isStandby() ? "standby" : "active";
    }

    @Test
    public void testReadSimpleSecret() {
        final ClientHttpRequestFactory factory = createFactory();

        final VaultTemplate template1 = new VaultTemplate(ourEndpoint1, factory, () -> VaultToken.of(ourRootToken));
        final VaultTemplate template2 = new VaultTemplate(ourEndpoint2, factory, () -> VaultToken.of(ourRootToken));
        assertModes(template1, template2);

        doCheckHealth(template1);
        template1.read("/secret/test");

        doCheckHealth(template2);
        template2.read("/secret/test"); // Should redirect to master node
    }

    @Test
    public void testWriteSimpleSecret() {
        final ClientHttpRequestFactory factory = createFactory();

        final VaultTemplate template1 = new VaultTemplate(ourEndpoint1, factory, () -> VaultToken.of(ourRootToken));
        final VaultTemplate template2 = new VaultTemplate(ourEndpoint2, factory, () -> VaultToken.of(ourRootToken));
        assertModes(template1, template2);

        doCheckHealth(template1);
        template1.write("/secret/test1", Collections.singletonMap("data", "simple")); // 201
        doCheckHealth(template2);
        template2.write("/secret/test2", Collections.singletonMap("data", "simple")); // 307


        // First secret could be read from both nodes
        then(template1.read("/secret/test1")).isNotNull();
        then(template2.read("/secret/test1")).isNotNull();

        // Second secret actually failed to create
        then(template1.read("/secret/test2")).isNotNull();
        then(template2.read("/secret/test2")).isNotNull();
    }

    private static void doCheckHealth(VaultTemplate template) {
        final VaultHealth health = template.opsForSys().health();
        then(health.isInitialized()).isTrue();
        then(health.isSealed()).isFalse();
    }

    @NotNull
    private static ClientHttpRequestFactory createFactory() {
        return ClientHttpRequestFactoryFactory.create(new ClientOptions(), SslConfiguration.unconfigured());
    }
}
