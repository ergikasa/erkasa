package com.erkasa.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/** 4.1: the V8 tables, the entities and the repositories work together on a real Postgres. */
class ProjectPersistenceTests extends AbstractIntegrationTest {

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private JobRepository jobs;

    @Autowired
    private JobStepRepository steps;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID newUser() {
        return UUID.fromString(jdbc.queryForObject(
                "insert into users (id, email, role) values (gen_random_uuid(), ?, 'USER') returning id::text",
                String.class, "jobs-" + UUID.randomUUID() + "@example.com"));
    }

    private Project saveProject(UUID userId) {
        return projects.save(Project.create(userId, "5 facts about Tirana", Language.SQ, 45,
                AspectRatio.PORTRAIT, null));
    }

    @Test
    void projectWithScriptJsonIsSavedAndReadBack() {
        UUID user = newUser();
        Project p = saveProject(user);
        p.moveTo(ProjectStatus.SCRIPT_REVIEW);
        p.setScript("5 fakte për Tiranën", "{\"title\":\"5 fakte për Tiranën\",\"scenes\":[]}");
        projects.save(p);

        Project loaded = projects.findByIdAndUserId(p.getId(), user).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ProjectStatus.SCRIPT_REVIEW);
        assertThat(loaded.getLanguage()).isEqualTo(Language.SQ);
        assertThat(loaded.getTitle()).isEqualTo("5 fakte për Tiranën");
        assertThat(loaded.getScript()).contains("\"scenes\"");
        assertThat(loaded.getCreatedAt()).isNotNull();

        // stored as real jsonb: Postgres can read inside it
        String title = jdbc.queryForObject("select script->>'title' from projects where id = ?",
                String.class, p.getId());
        assertThat(title).isEqualTo("5 fakte për Tiranën");
    }

    @Test
    void otherUsersCannotSeeTheProject() {
        Project p = saveProject(newUser());

        assertThat(projects.findByIdAndUserId(p.getId(), newUser())).isEmpty();
    }

    @Test
    void projectsAreListedNewestFirst() throws InterruptedException {
        UUID user = newUser();
        Project first = saveProject(user);
        Thread.sleep(5); // make sure the two created_at values differ
        Project second = saveProject(user);

        List<Project> list = projects.findByUserIdOrderByCreatedAtDesc(user, PageRequest.of(0, 10));

        assertThat(list).extracting(Project::getId).containsExactly(second.getId(), first.getId());
    }

    @Test
    void jobWithOneStepPerSceneAndType() {
        Project p = saveProject(newUser());
        Job job = jobs.save(Job.queue(p.getId(), JobKind.ASSETS));
        for (int scene = 0; scene < 3; scene++) {
            steps.save(JobStep.forScene(job.getId(), StepType.IMAGE, scene));
            steps.save(JobStep.forScene(job.getId(), StepType.VOICE, scene));
        }

        assertThat(steps.countByJobId(job.getId())).isEqualTo(6);
        assertThat(steps.findByJobIdOrderByTypeAscSceneIndexAsc(job.getId()))
                .extracting(s -> s.getType() + "#" + s.getSceneIndex())
                .containsExactly("IMAGE#0", "IMAGE#1", "IMAGE#2", "VOICE#0", "VOICE#1", "VOICE#2");
        assertThat(jobs.existsByProjectIdAndKindAndStatusIn(p.getId(), JobKind.ASSETS,
                EnumSet.of(JobStatus.QUEUED, JobStatus.RUNNING))).isTrue();
    }

    @Test
    void theSameSceneCannotGetTwoImagesInOneJob() {
        Project p = saveProject(newUser());
        Job job = jobs.save(Job.queue(p.getId(), JobKind.ASSETS));
        steps.saveAndFlush(JobStep.forScene(job.getId(), StepType.IMAGE, 0));

        assertThatThrownBy(() -> steps.saveAndFlush(JobStep.forScene(job.getId(), StepType.IMAGE, 0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stepResultAndCostAreSaved() {
        Project p = saveProject(newUser());
        Job job = jobs.save(Job.queue(p.getId(), JobKind.ASSETS));
        JobStep step = JobStep.forScene(job.getId(), StepType.VOICE, 1);
        step.moveTo(StepStatus.RUNNING);
        step.succeed("projects/" + p.getId() + "/scene-1.mp3",
                "{\"durationSeconds\":5.2,\"words\":[]}", new BigDecimal("0.0081"));
        steps.save(step);

        JobStep loaded = steps.findByJobIdAndTypeAndSceneIndex(job.getId(), StepType.VOICE, 1).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(loaded.getAttempts()).isEqualTo(1);
        assertThat(loaded.getCostUsd()).isEqualByComparingTo("0.0081");
        assertThat(loaded.getOutputMeta()).contains("durationSeconds");
    }

    @Test
    void deletingAProjectDeletesItsJobsAndSteps() {
        Project p = saveProject(newUser());
        Job job = jobs.save(Job.queue(p.getId(), JobKind.SCRIPT));
        steps.save(JobStep.forVideo(job.getId(), StepType.SCRIPT));

        jdbc.update("delete from projects where id = ?", p.getId());

        assertThat(jobs.findById(job.getId())).isEmpty();
        assertThat(steps.countByJobId(job.getId())).isZero();
    }

    @Test
    void databaseRejectsAnUnknownStatus() {
        Project p = saveProject(newUser());

        assertThatThrownBy(() -> jdbc.update("update projects set status = 'HACKED' where id = ?", p.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}