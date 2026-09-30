package com.autoservicehub.service;

import com.autoservicehub.service.impl.FollowupServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Nightly job that turns due follow-ups into in-app notifications.
 *
 * <p>The work itself lives in {@code FollowupServiceImpl.notifyDueFollowUps},
 * which owns the transaction; this class only supplies the trigger and logs
 * the outcome. Keeping the two apart means the same processing can be invoked
 * directly (by a test, or by a future manual "run now" endpoint) without going
 * through the scheduler.
 *
 * <p>Processing is idempotent: a follow-up is notified once, and re-running the
 * job with nothing newly due creates no further notifications.
 */
@Component
@RequiredArgsConstructor
public class FollowupNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(FollowupNotificationScheduler.class);

    private final FollowupServiceImpl followupService;

    /**
     * Runs shortly after midnight each day, so a follow-up that fell due during
     * the day is picked up the following morning.
     *
     * <p>The minute and hour are fixed rather than randomised deliberately: the
     * job is cheap (one indexed query plus a write per newly due follow-up) and
     * an predictable time is easier to reason about operationally.
     */
    @Scheduled(cron = "${app.followup.notify-cron:0 15 0 * * *}")
    @Transactional
    public void processDueFollowUps() {
        LocalDate today = LocalDate.now();
        int created = followupService.notifyDueFollowUps(today);
        if (created > 0) {
            log.info("Due follow-up processing created {} notification(s) as of {}", created, today);
        } else {
            log.debug("Due follow-up processing as of {} created no notifications", today);
        }
    }
}
