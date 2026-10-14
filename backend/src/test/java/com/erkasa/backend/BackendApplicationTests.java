package com.erkasa.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import com.erkasa.backend.assets.StorageHealthIndicator;

/**
 * Phase 1 "done" check: the whole backend starts against real Postgres, RabbitMQ and S3 storage,
 * each running in a throwaway Docker container that Testcontainers starts and removes automatically.
 * Your docker compose data is never touched.
 *
 * Needs Docker Desktop running.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.show-details=always")
class BackendApplicationTests {

    private static final String DB_NAME = "erkasa_test";
    private static final String DB_USER = "test";
    private static final String DB_PASSWORD = "test";
    private static final String MQ_USER = "test";
    private static final String MQ_PASSWORD = "test";
    private static final String S3_ACCESS_KEY = "test-access";
    private static final String S3_SECRET_KEY = "test-secret";
    private static final String S3_BUCKET = "erkasa-test";

    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", DB_NAME)
            .withEnv("POSTGRES_USER", DB_USER)
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withExposedPorts(5432)
            // Postgres prints this twice: once for the init run, once when it is really ready.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", MQ_USER)
            .withEnv("RABBITMQ_DEFAULT_PASS", MQ_PASSWORD)
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    static final GenericContainer<?> STORAGE = new GenericContainer<>("chrislusf/seaweedfs:latest")
            .withCommand("server -dir=/data -s3 -s3.port=8333 -ip.bind=0.0.0.0 -master.volumeSizeLimitMB=64")
            .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
            .withExposedPorts(8333, 9333)
            // Same check as the docker-compose healthcheck: the SeaweedFS master answers on 9333.
            .waitingFor(Wait.forHttp("/cluster/status").forPort(9333)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        RABBIT.start();
        STORAGE.start();
        waitForS3Gateway();
    }

    /** The S3 gateway (port 8333) comes up a few seconds after the master; wait until it answers. */
    private static void waitForS3Gateway() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI s3 = URI.create("http://%s:%d/".formatted(STORAGE.getHost(), STORAGE.getMappedPort(8333)));
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpRequest ping = HttpRequest.newBuilder(s3).timeout(Duration.ofSeconds(3)).GET().build();
                client.send(ping, HttpResponse.BodyHandlers.discarding());
                return; // any HTTP answer (even 403) means the gateway is up
            } catch (Exception notReadyYet) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw new IllegalStateException("SeaweedFS S3 gateway did not start within 2 minutes. Logs:\n"
                + STORAGE.getLogs());
    }

    /** Points the app at the test containers instead of the values in infra/.env. */
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://%s:%d/%s"
                .formatted(POSTGRES.getHost(), POSTGRES.getMappedPort(5432), DB_NAME));
        registry.add("spring.datasource.username", () -> DB_USER);
        registry.add("spring.datasource.password", () -> DB_PASSWORD);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> MQ_USER);
        registry.add("spring.rabbitmq.password", () -> MQ_PASSWORD);

        registry.add("erkasa.storage.endpoint", () -> "http://%s:%d"
                .formatted(STORAGE.getHost(), STORAGE.getMappedPort(8333)));
        registry.add("erkasa.storage.region", () -> "us-east-1");
        registry.add("erkasa.storage.bucket", () -> S3_BUCKET);
        registry.add("erkasa.storage.access-key", () -> S3_ACCESS_KEY);
        registry.add("erkasa.storage.secret-key", () -> S3_SECRET_KEY);
    }

    @Autowired
    private Environment env;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private StorageHealthIndicator storageHealth;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void flywayAppliedTheFirstMigration() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void rabbitMqConnectionIsOpen() {
        Boolean open = rabbit.execute(channel -> channel.isOpen());
        assertThat(open).isTrue();
    }

    @Test
    void storageBucketIsReachable() {
        assertThat(storageHealth.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthEndpointIsUpAndReturnsRequestId() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"storage\"", "\"db\"", "\"rabbit\"");
        assertThat(response.body()).endsWith("\"status\":\"UP\"}");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void unknownUrlReturnsOurErrorFormat() throws Exception {
        HttpResponse<String> response = get("/api/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"", "\"requestId\"", "\"status\":404");
    }

    @Test
    void validIncomingRequestIdIsKept() throws Exception {
        String myId = "my-request-12345";
        HttpRequest request = HttpRequest.newBuilder(uri("/actuator/health"))
                .header("X-Request-Id", myId)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id")).hasValue(myId);
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        String port = env.getRequiredProperty("local.server.port");
        return URI.create("http://localhost:" + port + path);
    }
}