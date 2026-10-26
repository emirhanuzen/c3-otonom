package com.otonomc3.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the telemetry ingestion, under {@code otonom.telemetry.*}.
 *
 * @param lateThreshold a message is "late" when it is received more than this after its timestamp
 * @param batchSize     maximum number of messages handed to the listener at once
 * @param batchTimeout  maximum time to wait for a batch to fill up before handing over a partial one
 * @param prefetch      number of unacknowledged messages RabbitMQ may push to the consumer
 */
@ConfigurationProperties("otonom.telemetry")
public record TelemetryProperties(
		@DefaultValue("2s") Duration lateThreshold,
		@DefaultValue("100") int batchSize,
		@DefaultValue("1s") Duration batchTimeout,
		@DefaultValue("250") int prefetch) {
}
