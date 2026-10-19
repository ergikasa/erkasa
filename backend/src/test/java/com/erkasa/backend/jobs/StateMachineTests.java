package com.erkasa.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

/** 4.1: which status may follow which. No database needed. */
class StateMachineTests {

    private static Project project() {
        return Project.create(UUID.randomUUID(), "idea", Language.EN, 30, AspectRatio.PORTRAIT, null);
    }

    // ---------- project ----------

    @Test
    void projectHappyPath() {
        Project p = project();
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.SCRIPT_PENDING);

        p.moveTo(ProjectStatus.SCRIPT_REVIEW);
        p.moveTo(ProjectStatus.ASSETS_PENDING);
        p.moveTo(ProjectStatus.ASSETS_READY);
        p.moveTo(ProjectStatus.RENDERING);
        p.moveTo(ProjectStatus.DONE);

        assertThat(p.getStatus()).isEqualTo(ProjectStatus.DONE);
        assertThat(p.getStatus().isFinal()).isTrue();
    }

    @Test
    void scriptCannotBeApprovedWhileItIsBeingWritten() {
        Project p = project();

        assertThatThrownBy(() -> p.moveTo(ProjectStatus.ASSETS_PENDING))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("SCRIPT_PENDING to ASSETS_PENDING");
        assertThat(p.getStatus()).isEqualTo(ProjectStatus.SCRIPT_PENDING);
    }

    @Test
    void reviewCanAskForANewScript() {
        Project p = project();
        p.moveTo(ProjectStatus.SCRIPT_REVIEW);

        p.moveTo(ProjectStatus.SCRIPT_PENDING);

        assertThat(p.getStatus()).isEqualTo(ProjectStatus.SCRIPT_PENDING);
    }

    @Test
    void failedProjectKeepsTheErrorUntilRetried() {
        Project p = project();
        p.fail("PROVIDER_FAILED", "anthropic TIMEOUT");

        assertThat(p.getStatus()).isEqualTo(ProjectStatus.FAILED);
        assertThat(p.getErrorCode()).isEqualTo("PROVIDER_FAILED");

        p.moveTo(ProjectStatus.SCRIPT_PENDING);
        assertThat(p.getErrorCode()).isNull();
        assertThat(p.getErrorMessage()).isNull();
    }

    @Test
    void cancelledAndDoneAreFinal() {
        Project p = project();
        p.moveTo(ProjectStatus.CANCELLED);

        assertThat(ProjectStatus.CANCELLED.next()).isEmpty();
        assertThat(ProjectStatus.DONE.next()).isEmpty();
        assertThatThrownBy(() -> p.moveTo(ProjectStatus.SCRIPT_PENDING))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void everyWorkingStateCanBeCancelled() {
        for (ProjectStatus s : ProjectStatus.values()) {
            if (!s.isFinal()) {
                assertThat(s.canMoveTo(ProjectStatus.CANCELLED)).as("%s -> CANCELLED", s).isTrue();
            }
        }
    }

    @Test
    void longErrorMessagesAreShortened() {
        Project p = project();
        p.fail("X", "a".repeat(2000));

        assertThat(p.getErrorMessage()).hasSize(500).endsWith("...");
    }

    // ---------- job ----------

    @Test
    void jobRunsAndRecordsTimes() {
        Job job = Job.queue(UUID.randomUUID(), JobKind.ASSETS);
        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);

        job.moveTo(JobStatus.RUNNING);
        assertThat(job.getStartedAt()).isNotNull();
        assertThat(job.getFinishedAt()).isNull();

        job.moveTo(JobStatus.SUCCEEDED);
        assertThat(job.getFinishedAt()).isNotNull();
        assertThat(job.getStatus().isActive()).isFalse();
    }

    @Test
    void failedJobCanBeQueuedAgainButSucceededCannot() {
        Job job = Job.queue(UUID.randomUUID(), JobKind.SCRIPT);
        job.moveTo(JobStatus.RUNNING);
        job.fail("TIMEOUT", "slow");

        job.moveTo(JobStatus.QUEUED);
        assertThat(job.getStartedAt()).isNull();

        job.moveTo(JobStatus.RUNNING);
        job.moveTo(JobStatus.SUCCEEDED);
        assertThatThrownBy(() -> job.moveTo(JobStatus.QUEUED)).isInstanceOf(InvalidTransitionException.class);
    }

    // ---------- step ----------

    @Test
    void stepCountsAttemptsAndAddsUpCost() {
        JobStep step = JobStep.forScene(UUID.randomUUID(), StepType.IMAGE, 2);

        step.moveTo(StepStatus.RUNNING);
        step.fail("NO_IMAGE", "blocked", new BigDecimal("0.08"));
        step.moveTo(StepStatus.PENDING);
        step.moveTo(StepStatus.RUNNING);
        step.succeed("projects/p/scene-2.png", "{\"width\":1024}", new BigDecimal("0.08"));

        assertThat(step.getAttempts()).isEqualTo(2);
        assertThat(step.getCostUsd()).isEqualByComparingTo("0.16");
        assertThat(step.getOutputKey()).isEqualTo("projects/p/scene-2.png");
        assertThat(step.getErrorCode()).isNull();
        assertThat(step.getStatus().isDone()).isTrue();
    }

    @Test
    void succeededStepIsNeverRunAgain() {
        JobStep step = JobStep.forVideo(UUID.randomUUID(), StepType.SCRIPT);
        step.moveTo(StepStatus.RUNNING);
        step.succeed(null, "{}", BigDecimal.ZERO);

        assertThatThrownBy(() -> step.moveTo(StepStatus.RUNNING)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> step.moveTo(StepStatus.PENDING)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void crashedStepCanGoBackToPending() {
        JobStep step = JobStep.forScene(UUID.randomUUID(), StepType.VOICE, 0);
        step.moveTo(StepStatus.RUNNING);

        step.moveTo(StepStatus.PENDING);

        assertThat(step.getStatus()).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void sceneIndexRules() {
        UUID job = UUID.randomUUID();

        assertThat(JobStep.forVideo(job, StepType.SCRIPT).getSceneIndex()).isEqualTo(JobStep.WHOLE_VIDEO);
        assertThatThrownBy(() -> JobStep.forVideo(job, StepType.IMAGE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobStep.forScene(job, StepType.SCRIPT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobStep.forScene(job, StepType.VOICE, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}