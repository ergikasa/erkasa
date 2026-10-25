package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.features.promptvideo.project.ProjectService;
import com.erkasa.backend.features.promptvideo.script.ScriptGenerator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;

/** 4.2: when the LLM fails, the project shows why, and the user can try again. */
class ScriptJobFailureTests extends AbstractIntegrationTest {

    @MockitoBean
    private ScriptGenerator scripts;

    @Autowired
    private ProjectService service;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID newUser() {
        return UUID.fromString(jdbc.queryForObject(
                "insert into users (id, email, role) values (gen_random_uuid(), ?, 'USER') returning id::text",
                String.class, "fail-" + UUID.randomUUID() + "@example.com"));
    }

    private Project waitFor(UUID projectId, ProjectStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Project p = null;
        while (System.currentTimeMillis() < deadline) {
            p = projects.findById(projectId).orElseThrow();
            if (p.getStatus() == status) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Project never reached " + status + ", last: " + (p == null ? null : p.getStatus()));
    }

    @Test
    void providerFailureMarksProjectJobAndStepFailedAndRetryWorks() throws Exception {
        when(scripts.generate(any(), any()))
                .thenThrow(new ProviderException("anthropic", "TIMEOUT", "no answer", true, null))
                .thenReturn(new VideoScript("Second try", "calm", List.of(
                        new VideoScript.Scene("One.", "", "a"),
                        new VideoScript.Scene("Two.", "", "b"),
                        new VideoScript.Scene("Three.", "", "c"))));
        UUID user = newUser();

        Project created = service.create(user, new ProjectService.NewProject("idea", Language.EN, 30, null, null, null));
        Project failed = waitFor(created.getId(), ProjectStatus.FAILED);

        assertThat(failed.getErrorCode()).isEqualTo("TIMEOUT");
        List<Job> history = jobs.findByProjectIdOrderByCreatedAtAsc(created.getId());
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getStatus()).isEqualTo(JobStatus.FAILED);
        String step = jdbc.queryForObject("select status from job_steps where job_id = ?",
                String.class, history.get(0).getId());
        assertThat(step).isEqualTo("FAILED");

        // the user presses "try again"
        service.regenerateScript(user, created.getId());
        Project review = waitFor(created.getId(), ProjectStatus.SCRIPT_REVIEW);

        assertThat(review.getTitle()).isEqualTo("Second try");
        assertThat(review.getErrorCode()).isNull();
        assertThat(jobs.findByProjectIdOrderByCreatedAtAsc(created.getId())).extracting(Job::getStatus)
                .containsExactly(JobStatus.FAILED, JobStatus.SUCCEEDED);
    }

    @Test
    void atMostFiveScriptsPerProject() throws Exception {
        when(scripts.generate(any(), any()))
                .thenThrow(new ProviderException("anthropic", "TIMEOUT", "no answer", true, null));
        UUID user = newUser();
        Project p = service.create(user, new ProjectService.NewProject("idea", Language.EN, 30, null, null, null));

        for (int i = 0; i < 4; i++) {
            waitFor(p.getId(), ProjectStatus.FAILED);
            service.regenerateScript(user, p.getId());
        }
        waitFor(p.getId(), ProjectStatus.FAILED);

        try {
            service.regenerateScript(user, p.getId());
            throw new AssertionError("6th script should be refused");
        } catch (com.erkasa.backend.common.error.ApiException e) {
            assertThat(e.getCode()).isEqualTo("TOO_MANY_SCRIPTS");
        }
    }
}