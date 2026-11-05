package com.otonomc3.core.command.messaging;

import com.otonomc3.core.command.dto.CommandResultMessageDto;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.config.RabbitConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes command results from core.command.results.
 *
 * <p>Nothing is ever thrown back to the container: an unreadable message or a
 * database error would otherwise be requeued in a tight loop. A result lost that
 * way leaves its command PENDING until the timeout job marks it TIMEOUT.
 */
@Slf4j
@Component
public class CommandResultListener {

	private final JsonMapper jsonMapper;
	private final CommandService service;

	public CommandResultListener(JsonMapper jsonMapper, CommandService service) {
		this.jsonMapper = jsonMapper;
		this.service = service;
	}

	@RabbitListener(queues = RabbitConfig.COMMAND_RESULTS_QUEUE)
	public void onMessage(Message message) {
		CommandResultMessageDto result;
		try {
			result = jsonMapper.readValue(message.getBody(), CommandResultMessageDto.class);
		} catch (JacksonException e) {
			log.warn("Skipping command result (routing key {}): invalid JSON: {}",
					message.getMessageProperties().getReceivedRoutingKey(), e.getOriginalMessage());
			return;
		}
		if (result == null) {
			log.warn("Skipping empty command result");
			return;
		}
		try {
			service.applyResult(result);
		} catch (DataAccessException e) {
			log.error("Could not store the result of command {}: {}", result.commandId(), e.toString());
		}
	}

}
