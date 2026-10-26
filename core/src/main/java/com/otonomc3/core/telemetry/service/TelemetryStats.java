package com.otonomc3.core.telemetry.service;

import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ingestion counters, logged as one summary line every 10 seconds and then reset. */
@Slf4j
@Component
public class TelemetryStats {

	private final AtomicLong received = new AtomicLong();
	private final AtomicLong saved = new AtomicLong();
	private final AtomicLong duplicates = new AtomicLong();
	private final AtomicLong late = new AtomicLong();
	private final AtomicLong deadLettered = new AtomicLong();

	/** Messages taken from the queue, valid or not. */
	public void addReceived(long count) {
		received.addAndGet(count);
	}

	public void addSaved(long count) {
		saved.addAndGet(count);
	}

	/** Messages skipped because they were already in the batch or in the database. */
	public void addDuplicates(long count) {
		duplicates.addAndGet(count);
	}

	/** Saved messages that arrived later than the late threshold. */
	public void addLate(long count) {
		late.addAndGet(count);
	}

	/** Messages that could not be read and were sent to the dead letter exchange. */
	public void addDeadLettered(long count) {
		deadLettered.addAndGet(count);
	}

	@Scheduled(fixedRate = 10_000, initialDelay = 10_000)
	void logSummary() {
		log.info("Telemetry (last 10s): received={}, saved={}, duplicates={}, late={}, deadLettered={}",
				received.getAndSet(0), saved.getAndSet(0), duplicates.getAndSet(0),
				late.getAndSet(0), deadLettered.getAndSet(0));
	}

}
