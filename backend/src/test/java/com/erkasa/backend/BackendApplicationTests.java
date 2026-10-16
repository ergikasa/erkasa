package com.erkasa.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.assets.StorageHealthIndicator;

/** Phase 1 checks: infrastructure, health, request ID and error format. */
class BackendApplicationTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private StorageHealthIndicator storageHealth;

    @Test
    void flywayAppliedAllMigrations() {
        Integer failed = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where not success", Integer.class);
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
        assertThat(failed).isZero();
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
}