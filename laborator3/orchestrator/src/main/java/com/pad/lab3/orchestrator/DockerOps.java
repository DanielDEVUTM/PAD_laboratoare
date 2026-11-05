package com.pad.lab3.orchestrator;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Thin wrapper over the Docker Engine API (through /var/run/docker.sock). */
@Component
public class DockerOps {

    public static final String LABEL_MANAGED = "lab3.managed";
    public static final String LABEL_WAREHOUSE = "lab3.warehouse";
    public static final String LABEL_ROLE = "lab3.role";

    private final DockerClient docker;
    private final String network;
    private final String composeProject;

    public DockerOps() {
        DefaultDockerClientConfig cfg = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("unix:///var/run/docker.sock").build();
        this.docker = DockerClientImpl.getInstance(cfg, new ZerodepDockerHttpClient.Builder()
                .dockerHost(cfg.getDockerHost()).sslConfig(cfg.getSSLConfig())
                .maxConnections(50).connectionTimeout(Duration.ofSeconds(5)).responseTimeout(Duration.ofSeconds(180)).build());
        // new containers join the same network (and compose project) as the orchestrator itself
        String net = null, proj = null;
        try {
            var self = docker.inspectContainerCmd(System.getenv().getOrDefault("HOSTNAME", "orchestrator")).exec();
            net = self.getNetworkSettings().getNetworks().keySet().iterator().next();
            proj = self.getConfig().getLabels() == null ? null : self.getConfig().getLabels().get("com.docker.compose.project");
        } catch (Exception e) {
            net = System.getenv().getOrDefault("DOCKER_NETWORK", "bridge");
        }
        this.network = net;
        this.composeProject = proj;
    }

    public String network() { return network; }

    public void ensureImage(String image) {
        try {
            docker.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            String name = image.contains(":") ? image.substring(0, image.indexOf(':')) : image;
            String tag = image.contains(":") ? image.substring(image.indexOf(':') + 1) : "latest";
            try {
                docker.pullImageCmd(name).withTag(tag).start().awaitCompletion(10, TimeUnit.MINUTES);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public List<Container> managed() {
        return docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(LABEL_MANAGED, "true")).exec();
    }

    public Container find(String containerName) {
        return managed().stream().filter(c -> java.util.Arrays.asList(c.getNames()).contains("/" + containerName))
                .findFirst().orElse(null);
    }

    public void createAndStart(String name, String warehouse, String role, String image,
                               List<String> env, List<String> cmd) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_MANAGED, "true");
        labels.put(LABEL_WAREHOUSE, warehouse);
        labels.put(LABEL_ROLE, role);
        if (composeProject != null) {   // lets `docker compose down --remove-orphans` clean them up
            labels.put("com.docker.compose.project", composeProject);
            labels.put("com.docker.compose.service", "warehouse-" + role);
            labels.put("com.docker.compose.oneoff", "False");
        }
        CreateContainerCmd c = docker.createContainerCmd(image).withName(name).withHostName(name)
                .withLabels(labels)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network)
                        .withRestartPolicy(RestartPolicy.unlessStoppedRestart()));
        if (env != null) c.withEnv(env);
        if (cmd != null) c.withCmd(cmd);
        String id = c.exec().getId();
        docker.startContainerCmd(id).exec();
    }

    public void start(String name) { docker.startContainerCmd(name).exec(); }

    public void stop(String name) { docker.stopContainerCmd(name).withTimeout(5).exec(); }

    public void pause(String name) { docker.pauseContainerCmd(name).exec(); }

    public void unpause(String name) { docker.unpauseContainerCmd(name).exec(); }

    public void remove(String name) {
        try {
            docker.removeContainerCmd(name).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException ignored) { }
    }

    /** Runs a mongosh script inside a database container and returns stdout+stderr. */
    public String mongosh(String container, String script) throws Exception {
        String execId = docker.execCreateCmd(container).withAttachStdout(true).withAttachStderr(true)
                .withCmd("mongosh", "--quiet", "--eval", script).exec().getId();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        docker.execStartCmd(execId).exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame f) {
                out.writeBytes(f.getPayload());
            }
        }).awaitCompletion(90, TimeUnit.SECONDS);
        return out.toString(StandardCharsets.UTF_8).trim();
    }
}
