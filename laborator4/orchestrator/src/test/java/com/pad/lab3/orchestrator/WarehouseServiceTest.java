package com.pad.lab3.orchestrator;

import com.github.dockerjava.api.model.Container;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WarehouseServiceTest {

    private final DockerOps docker = mock(DockerOps.class);

    private WarehouseService service(boolean publish, String advertiseHost, String bindIp) throws Exception {
        return new WarehouseService(docker, "dw:new", "mongo:7", "http://gateway:8000", "http://sync:8002",
                0, publish, 8101, 27101, false, advertiseHost, bindIp, 384);
    }

    private static Container api(String warehouse, String imageId, String hostPort) {
        Container c = mock(Container.class);
        when(c.getNames()).thenReturn(new String[]{"/" + warehouse + "-api"});
        when(c.getState()).thenReturn("running");
        when(c.getImageId()).thenReturn(imageId);
        when(c.getLabels()).thenReturn(Map.of(DockerOps.LABEL_WAREHOUSE, warehouse,
                DockerOps.LABEL_ROLE, "api", DockerOps.LABEL_HOSTPORT, hostPort));
        return c;
    }

    @SuppressWarnings("unchecked")
    private DockerOps.Publish capturePublish(ArgumentCaptor<List<String>> env) {
        ArgumentCaptor<DockerOps.Publish> publish = ArgumentCaptor.forClass(DockerOps.Publish.class);
        verify(docker).createAndStart(eq("wh-2-api"), eq("wh-2"), eq("api"), eq("dw:new"),
                env.capture(), isNull(), publish.capture(), eq(384L));
        return publish.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoMachineSetupPublishesOnThePrivateIpAndAdvertisesTheFreePort() throws Exception {
        Container used = api("wh-1", "sha256:new", "8101");
        when(docker.managed()).thenReturn(List.of(used));

        service(true, "10.10.2.10", "10.10.2.10").startApi("wh-2", List.of("NODE_ID=wh-2"));

        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        assertEquals(new DockerOps.Publish("10.10.2.10", 8102, 8080), capturePublish(env));
        assertEquals(List.of("NODE_ID=wh-2", "ADVERTISE_HOST=10.10.2.10", "ADVERTISE_PORT=8102"), env.getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleHostSetupAdvertisesNothing() throws Exception {
        service(true, "", "").startApi("wh-2", List.of("NODE_ID=wh-2"));

        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        assertEquals(new DockerOps.Publish("", 8101, 8080), capturePublish(env));
        assertEquals(List.of("NODE_ID=wh-2"), env.getValue());
    }

    @Test
    void advertiseHostWithoutPublishedPortsIsRejected() {
        assertThrows(IllegalStateException.class, () -> service(false, "10.10.2.10", ""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void directLocalhostAddressIsOnlyOfferedOnASingleHost() throws Exception {
        Container c = api("wh-1", "sha256:new", "8101");
        when(docker.managed()).thenReturn(List.of(c));

        Map<String, Object> local = (Map<String, Object>) service(true, "", "").list().get(0).get("api");
        Map<String, Object> cloud = (Map<String, Object>) service(true, "10.10.2.10", "").list().get(0).get("api");

        assertEquals("http://localhost:8101", local.get("url"));
        assertFalse(cloud.containsKey("url"));
    }

    @Test
    void onlyApisRunningAnOldImageAreReplaced() throws Exception {
        Container current = api("wh-1", "sha256:new", "8101");
        Container outdated = api("wh-2", "sha256:old", "8102");
        when(docker.managed()).thenReturn(List.of(current, outdated), List.of(current));
        when(docker.imageId("dw:new")).thenReturn("sha256:new");

        service(true, "", "").refreshOutdatedApis();

        verify(docker).stop("wh-2-api");
        verify(docker).remove("wh-2-api");
        verify(docker).createAndStart(eq("wh-2-api"), eq("wh-2"), eq("api"), eq("dw:new"),
                anyList(), isNull(), any(DockerOps.Publish.class), eq(384L));
        verify(docker, never()).stop("wh-1-api");
        verify(docker, never()).remove("wh-1-api");
    }
}
