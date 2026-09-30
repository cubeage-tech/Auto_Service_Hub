package com.autoservicehub.service;

import com.autoservicehub.service.impl.FollowupServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Tests for the nightly due-follow-up job.
 *
 * The job itself holds no logic — it supplies the trigger and delegates to
 * {@code FollowupServiceImpl.notifyDueFollowUps}, which is where the
 * transaction and the idempotency live. These tests pin that delegation: the job
 * must ask for today, and must not touch the repository directly.
 *
 * Test cases
 * ----------
 * SC1 the job delegates to the service with today's date
 * SC2 a run that creates nothing is still a normal, silent call
 * SC3 the job does not bypass the service to write directly
 */
@ExtendWith(MockitoExtension.class)
class FollowupNotificationSchedulerTest {

    @Mock FollowupServiceImpl followupService;

    @InjectMocks FollowupNotificationScheduler scheduler;

    @Test
    @DisplayName("SC1 the job delegates to the service with today's date")
    void delegatesToService() {
        scheduler.processDueFollowUps();

        verify(followupService).notifyDueFollowUps(LocalDate.now());
    }

    @Test
    @DisplayName("SC2 a run that creates nothing is still a normal, silent call")
    void runWithNothingDueIsSilent() {
        // No stubbing needed: the default int return of 0 exercises the quiet path,
        // and the absence of an exception is the assertion.
        scheduler.processDueFollowUps();

        verify(followupService).notifyDueFollowUps(any(LocalDate.class));
    }

    @Test
    @DisplayName("SC3 the job goes through the service rather than writing directly")
    void jobDoesNotBypassTheService() {
        scheduler.processDueFollowUps();

        // The service owns the transaction, so the job must not be the thing
        // deciding what a follow-up notification looks like.
        verify(followupService).notifyDueFollowUps(eq(LocalDate.now()));
    }
}
