package com.autoservicehub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring's scheduled-method support.
 *
 * <p>The application had no scheduling at all before the due-follow-up job, so
 * this is the single place that turns {@code @Scheduled} on. The trigger
 * expression itself lives on the job, not here, so the schedule can be
 * changed in one obvious place.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
