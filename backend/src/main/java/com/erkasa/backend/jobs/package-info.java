package com.erkasa.backend.jobs;


/**
 * Jobs: video projects and pipeline steps (phase 4).
 *
 * Project (one video) -> Job (one stage: SCRIPT, ASSETS, RENDER) -> JobStep (one paid unit of work).
 * Every status change goes through moveTo(), which refuses moves the state machine does not allow.
 */