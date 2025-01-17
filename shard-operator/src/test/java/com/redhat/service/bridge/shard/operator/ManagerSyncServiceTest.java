package com.redhat.service.bridge.shard.operator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.redhat.service.bridge.infra.api.APIConstants;
import com.redhat.service.bridge.infra.models.dto.BridgeDTO;
import com.redhat.service.bridge.infra.models.dto.ManagedResourceStatus;
import com.redhat.service.bridge.infra.models.dto.ProcessorDTO;
import com.redhat.service.bridge.shard.operator.providers.CustomerNamespaceProvider;
import com.redhat.service.bridge.shard.operator.resources.BridgeExecutor;
import com.redhat.service.bridge.shard.operator.resources.BridgeIngress;
import com.redhat.service.bridge.shard.operator.utils.KubernetesResourcePatcher;
import com.redhat.service.bridge.test.resource.KeycloakResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithOpenShiftTestServer;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@WithOpenShiftTestServer
@QuarkusTestResource(value = KeycloakResource.class, restrictToAnnotatedClass = true)
public class ManagerSyncServiceTest extends AbstractShardWireMockTest {

    @Inject
    CustomerNamespaceProvider customerNamespaceProvider;

    @Inject
    KubernetesResourcePatcher kubernetesResourcePatcher;

    @BeforeEach
    public void setup() {
        // Kubernetes Server must be cleaned up at startup of every test.
        kubernetesResourcePatcher.cleanUp();
    }

    @Test
    @WithPrometheus
    public void testBridgesAreDeployed() throws JsonProcessingException, InterruptedException {
        List<BridgeDTO> bridgeDTOS = new ArrayList<>();
        bridgeDTOS.add(new BridgeDTO("bridgesDeployed-1", "myName-1", "myEndpoint", TestSupport.CUSTOMER_ID, ManagedResourceStatus.ACCEPTED, TestSupport.KAFKA_CONNECTION_DTO));
        bridgeDTOS.add(new BridgeDTO("bridgesDeployed-2", "myName-2", "myEndpoint", TestSupport.CUSTOMER_ID, ManagedResourceStatus.ACCEPTED, TestSupport.KAFKA_CONNECTION_DTO));
        stubBridgesToDeployOrDelete(bridgeDTOS);
        stubBridgeUpdate();
        String expectedJsonUpdateProvisioningRequest =
                String.format("{\"id\": \"bridgesDeployed-1\", \"name\": \"myName-1\", \"endpoint\": \"myEndpoint\", \"customerId\": \"%s\", \"status\": \"provisioning\"}", TestSupport.CUSTOMER_ID);
        String expectedJsonUpdateAvailableRequest =
                String.format("{\"id\": \"bridgesDeployed-1\", \"name\": \"myName-1\", \"endpoint\": \"http://192.168.2.49/ob-bridgesdeployed-1\", \"customerId\": \"%s\", \"status\": \"ready\"}",
                        TestSupport.CUSTOMER_ID);

        CountDownLatch latch = new CountDownLatch(4); // Four updates to the manager are expected (2 PROVISIONING + 2 READY)
        addBridgeUpdateRequestListener(latch);

        managerSyncService.fetchAndProcessBridgesToDeployOrDelete().await().atMost(Duration.ofSeconds(5));

        String customerNamespace = customerNamespaceProvider.resolveName(TestSupport.CUSTOMER_ID);
        String firstBridgeName = BridgeIngress.resolveResourceName("bridgesDeployed-1");
        String secondBridgeName = BridgeIngress.resolveResourceName("bridgesDeployed-2");
        Awaitility.await()
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            kubernetesResourcePatcher.patchReadyDeploymentAsReady(firstBridgeName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyDeploymentAsReady(secondBridgeName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyService(firstBridgeName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyService(secondBridgeName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyNetworkResource(firstBridgeName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyNetworkResource(secondBridgeName, customerNamespace);
                        });

        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH))
                .withRequestBody(equalToJson(expectedJsonUpdateProvisioningRequest, true, true))
                .withHeader("Content-Type", equalTo("application/json")));
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH))
                .withRequestBody(equalToJson(expectedJsonUpdateAvailableRequest, true, true))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    public void testBridgesAreDeleted() throws JsonProcessingException, InterruptedException {
        List<BridgeDTO> bridgeDTOS = new ArrayList<>();
        bridgeDTOS.add(new BridgeDTO("bridgesDeleted-1", "myName-1", "myEndpoint", "myCustomerId", ManagedResourceStatus.DEPROVISION, TestSupport.KAFKA_CONNECTION_DTO));
        bridgeDTOS.add(new BridgeDTO("bridgesDeleted-2", "myName-2", "myEndpoint", "myCustomerId", ManagedResourceStatus.DEPROVISION, TestSupport.KAFKA_CONNECTION_DTO));
        stubBridgesToDeployOrDelete(bridgeDTOS);
        stubBridgeUpdate();
        String expectedJsonUpdateRequest = "{\"id\": \"bridgesDeleted-1\", \"name\": \"myName-1\", \"endpoint\": \"myEndpoint\", \"customerId\": \"myCustomerId\", \"status\": \"deleting\"}";

        CountDownLatch latch = new CountDownLatch(2); // Two updates to the manager are expected
        addBridgeUpdateRequestListener(latch);

        managerSyncService.fetchAndProcessBridgesToDeployOrDelete().await().atMost(Duration.ofSeconds(5));

        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH))
                .withRequestBody(equalToJson(expectedJsonUpdateRequest, true, true))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    public void testNotifyBridgeStatusChange() throws InterruptedException {
        BridgeDTO dto = new BridgeDTO("bridgeStatusChange-1", "myName-1", "myEndpoint", "myCustomerId", ManagedResourceStatus.PROVISIONING, TestSupport.KAFKA_CONNECTION_DTO);
        stubBridgeUpdate();
        String expectedJsonUpdate = "{\"id\": \"bridgeStatusChange-1\", \"name\": \"myName-1\", \"endpoint\": \"myEndpoint\", \"customerId\": \"myCustomerId\", \"status\": \"provisioning\"}";

        CountDownLatch latch = new CountDownLatch(1); // One update to the manager is expected
        addBridgeUpdateRequestListener(latch);

        managerSyncService.notifyBridgeStatusChange(dto).await().atMost(Duration.ofSeconds(5));

        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH))
                .withRequestBody(equalToJson(expectedJsonUpdate, true, true))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    @WithPrometheus
    public void testProcessorsAreDeployed() throws Exception {
        ProcessorDTO processor = TestSupport.newRequestedProcessorDTO();

        stubProcessorsToDeployOrDelete(Collections.singletonList(processor));
        stubProcessorUpdate();

        CountDownLatch latch = new CountDownLatch(2); // Two updates to the manager are expected (1 provisioning + 1 ready)
        addProcessorUpdateRequestListener(latch);
        managerSyncService.fetchAndProcessProcessorsToDeployOrDelete().await().atMost(Duration.ofSeconds(5));

        String customerNamespace = customerNamespaceProvider.resolveName(TestSupport.CUSTOMER_ID);
        String sanitizedName = BridgeExecutor.resolveResourceName(processor.getId());
        Awaitility.await()
                .atMost(Duration.ofMinutes(3))
                .pollInterval(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> {
                            kubernetesResourcePatcher.patchReadyDeploymentAsReady(sanitizedName, customerNamespace);
                            kubernetesResourcePatcher.patchReadyService(sanitizedName, customerNamespace);
                        });
        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        processor.setStatus(ManagedResourceStatus.READY);
        processor.setKafkaConnection(null); // the kafka connection is not included in the shard update for the manager
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH + "processors"))
                .withRequestBody(equalToJson(objectMapper.writeValueAsString(processor), true, true))
                .withHeader("Content-Type", equalTo("application/json")));
    }

    @Test
    public void notifyProcessorStatusChange() throws Exception {
        ProcessorDTO processor = TestSupport.newRequestedProcessorDTO();
        stubProcessorUpdate();

        CountDownLatch latch = new CountDownLatch(1); // One update to the manager is expected
        addProcessorUpdateRequestListener(latch);

        managerSyncService.notifyProcessorStatusChange(processor).await().atMost(Duration.ofSeconds(5));

        assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        wireMockServer.verify(putRequestedFor(urlEqualTo(APIConstants.SHARD_API_BASE_PATH + "processors"))
                .withRequestBody(equalToJson(objectMapper.writeValueAsString(processor), true, true))
                .withHeader("Content-Type", equalTo("application/json")));
    }
}
