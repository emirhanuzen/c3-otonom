package com.otonomc3.core.telemetry.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.otonomc3.core.config.RabbitConfig;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import com.otonomc3.core.telemetry.service.TelemetryService;
import com.otonomc3.core.telemetry.service.TelemetryStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes telemetry batches from the core.telemetry queue.
 *
 * <p>Unreadable messages are sent to the dead letter exchange one by one; the
 * rest of the batch goes to {@link TelemetryService}. The batch is acked only
 * after this method returns, so a failure puts the whole batch back on the queue.
 */
@Slf4j
@Component
public class TelemetryListener {

	/** Pause before a failed batch goes back to the queue, so a database outage does not cause a tight retry loop. */
	private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

	static final String REJECT_REASON_HEADER = "x-reject-reason";

	private final JsonMapper jsonMapper;
	private final TelemetryService service;
	private final TelemetryStats stats;
	private final RabbitTemplate rabbitTemplate;

	public TelemetryListener(JsonMapper jsonMapper, TelemetryService service, TelemetryStats stats,
			RabbitTemplate rabbitTemplate) {
		this.jsonMapper = jsonMapper;
		this.service = service;
		this.stats = stats;
		this.rabbitTemplate = rabbitTemplate;
	}

	@RabbitListener(queues = RabbitConfig.TELEMETRY_QUEUE, containerFactory = RabbitConfig.TELEMETRY_CONTAINER_FACTORY)
	public void onBatch(List<Message> batch) {
		Instant receivedAt = Instant.now();
		stats.addReceived(batch.size());

		List<TelemetryMessageDto> parsed = new ArrayList<>(batch.size());
		List<Message> rejected = new ArrayList<>();
		for (Message message : batch) {
			TelemetryMessageDto telemetry = parse(message);
			if (telemetry != null) {
				parsed.add(telemetry);
			} else {
				rejected.add(message);
			}
		}

		try {
			service.process(parsed, receivedAt);
		} catch (DataAccessException | TransactionException e) {
			log.error("Saving a batch of {} telemetry message(s) failed, requeueing it in {}s: {}",
					batch.size(), RETRY_DELAY.toSeconds(), e.toString());
			pauseBeforeRetry();
			throw e;
		}

		// Dead-lettered only after the batch is saved: if saving fails, the whole
		// batch (rejected messages included) is redelivered and nothing is sent twice.
		rejected.forEach(this::deadLetter);
	}

	/** Returns null, after logging, when the message is not valid schema version 2 JSON. */
	private TelemetryMessageDto parse(Message message) {
		String reason;
		try {
			TelemetryMessageDto telemetry = jsonMapper.readValue(message.getBody(), TelemetryMessageDto.class);
			if (telemetry == null) {
				reason = "empty message";
			} else if (telemetry.schemaVersion() == null
					|| telemetry.schemaVersion() != TelemetryMessageDto.SCHEMA_VERSION) {
				reason = "unsupported schemaVersion " + telemetry.schemaVersion();
			} else {
				return telemetry;
			}
		} catch (JacksonException e) {
			reason = "invalid JSON: " + e.getOriginalMessage();
		}
		log.warn("Rejecting telemetry message (routing key {}): {}",
				message.getMessageProperties().getReceivedRoutingKey(), reason);
		message.getMessageProperties().setHeader(REJECT_REASON_HEADER, reason);
		return null;
	}

	/** Sends the raw message, body unchanged, to the dead letter exchange. */
	private void deadLetter(Message message) {
		rabbitTemplate.send(RabbitConfig.DEAD_LETTER_EXCHANGE, RabbitConfig.TELEMETRY_DLQ_ROUTING_KEY, message);
		stats.addDeadLettered(1);
	}

	private static void pauseBeforeRetry() {
		try {
			Thread.sleep(RETRY_DELAY);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
