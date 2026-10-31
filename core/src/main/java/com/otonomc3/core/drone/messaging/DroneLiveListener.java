package com.otonomc3.core.drone.messaging;

import com.otonomc3.core.config.RabbitConfig;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes the core.live queue one message at a time and updates the live drone
 * state. It never touches the database; unreadable messages are logged and
 * skipped (core.telemetry already dead-letters them).
 */
@Slf4j
@Component
public class DroneLiveListener {

	private final JsonMapper jsonMapper;
	private final DroneLiveStateService service;

	public DroneLiveListener(JsonMapper jsonMapper, DroneLiveStateService service) {
		this.jsonMapper = jsonMapper;
		this.service = service;
	}

	@RabbitListener(queues = RabbitConfig.LIVE_QUEUE, containerFactory = RabbitConfig.LIVE_CONTAINER_FACTORY)
	public void onMessage(Message message) {
		TelemetryMessageDto telemetry = parse(message);
		if (telemetry != null) {
			service.update(telemetry);
		}
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
		log.warn("Skipping live telemetry message (routing key {}): {}",
				message.getMessageProperties().getReceivedRoutingKey(), reason);
		return null;
	}

}
