package com.otonomc3.core.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods, such as the periodic telemetry summary. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
