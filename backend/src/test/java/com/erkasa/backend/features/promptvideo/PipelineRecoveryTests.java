package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.features.promptvideo.project.PipelineRecovery;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/**
 * 4.4: jobs left half-done by a stopped server are finished on the next start, without
 * redoing steps that had already succeeded. We build the "after a crash" state by hand.
 */
class PipelineRecoveryTests extends AbstractIntegrationTest {

    private static final String SCRIPT = """
            {"title":"Recovered","musicMood":"calm","scenes":[
              {"narration":"One.","onScreenText":"","imagePrompt":"Mock scene 1"},
              {"narration":"Two.","onScreenText":"","imagePrompt":"Mock scene 2"}]}
            """;

    @Autowired
    private PipelineRecovery recovery;

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
                String.class, "recover-" + UUID.randomUUID() + "@example.com"));
    }

    private Project waitFor(UUID id, ProjectStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        Project p = null;
        while (System.currentTimeMillis() < deadline) {
            p = projects.findById(id).orElseThrow();
            if (p.getStatus() == status) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Never reached " + status + ", last: " + (p == null ? null : p.getStatus()));
    }

    private int calls(String kind, UUID jobId) {
        return jdbc.queryForObject("select count(*) from provider_usage where kind = ? and job_id = ?",
                Integer.class, kind, jobId);
    }

    @Test
    void scriptJobCaughtRunningIsFinished() throws Exception {
        Project p = projects.save(Project.create(newUser(), "crash during script", Language.EN, 30,
                AspectRatio.PORTRAIT, null));
        Job job = Job.queue(p.getId(), JobKind.SCRIPT);
        job.moveTo(JobStatus.RUNNING);
        job = jobs.save(job);
        JobStep step = JobStep.forVideo(job.getId(), StepType.SCRIPT);
        step.moveTo(StepStatus.RUNNING);
        steps.save(step);

        assertThat(recovery.recover()).isGreaterThanOrEqualTo(1);

        Project review = waitFor(p.getId(), ProjectStatus.SCRIPT_REVIEW);
        assertThat(review.getScript()).contains("scenes");
        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void assetsJobKeepsWhatWasDoneAndFinishesTheRest() throws Exception {
        Project p = Project.create(newUser(), "crash during assets", Language.EN, 30, AspectRatio.PORTRAIT, null);
        p.moveTo(ProjectStatus.SCRIPT_REVIEW);
        p.setScript("Recovered", SCRIPT);
        p.moveTo(ProjectStatus.ASSETS_PENDING);
        p = projects.save(p);

        Job job = Job.queue(p.getId(), JobKind.ASSETS);
        job.moveTo(JobStatus.RUNNING);
        job = jobs.save(job);
        UUID jobId = job.getId();

        // scene 1 image was finished before the crash, scene 1 voice was half-way, scene 2 not started
        JobStep doneImage = JobStep.forScene(jobId, StepType.IMAGE, 0);
        doneImage.moveTo(StepStatus.RUNNING);
        doneImage.succeed("projects/" + p.getId() + "/" + jobId + "/scene-0.png", "{\"width\":720}", new BigDecimal("0.08"));
        steps.save(doneImage);
        JobStep halfVoice = JobStep.forScene(jobId, StepType.VOICE, 0);
        halfVoice.moveTo(StepStatus.RUNNING);
        steps.save(halfVoice);
        steps.save(JobStep.forScene(jobId, StepType.IMAGE, 1));
        steps.save(JobStep.forScene(jobId, StepType.VOICE, 1));

        assertThat(recovery.recover()).isGreaterThanOrEqualTo(1);

        waitFor(p.getId(), ProjectStatus.ASSETS_READY);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        // the finished image was NOT generated again: only scene 2's image, both voices
        assertThat(calls("IMAGE", jobId)).isEqualTo(1);
        assertThat(calls("TTS", jobId)).isEqualTo(2);
        JobStep image0 = steps.findByJobIdAndTypeAndSceneIndex(jobId, StepType.IMAGE, 0).orElseThrow();
        assertThat(image0.getAttempts()).isEqualTo(1);
        assertThat(image0.getCostUsd()).isEqualByComparingTo("0.08");
        JobStep voice0 = steps.findByJobIdAndTypeAndSceneIndex(jobId, StepType.VOICE, 0).orElseThrow();
        assertThat(voice0.getAttempts()).isEqualTo(2);   // crashed try + recovered try
        assertThat(voice0.getStatus()).isEqualTo(StepStatus.SUCCEEDED);
    }

    @Test
    void finishedJobsAreLeftAlone() {
        Project p = projects.save(Project.create(newUser(), "all done", Language.EN, 30, AspectRatio.PORTRAIT, null));
        Job job = Job.queue(p.getId(), JobKind.SCRIPT);
        job.moveTo(JobStatus.RUNNING);
        job.moveTo(JobStatus.SUCCEEDED);
        job = jobs.save(job);

        recovery.recover();

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(projects.findById(p.getId()).orElseThrow().getStatus()).isEqualTo(ProjectStatus.SCRIPT_PENDING);
    }
}