package com.otonomc3.core.command.messaging;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.otonomc3.core.command.dto.CommandMessageDto;
import com.otonomc3.core.config.CommandProperties;
import com.otonomc3.core.config.RabbitConfig;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes commands to otonom.command with routing key command.&lt;droneId&gt;.
 *
 * <p>Each publish waits for the broker's publisher confirm. The template is
 * mandatory (spring.rabbitmq.template.mandatory), so a command no queue is bound
 * for (the edge adapter has never started) comes back as returned; RabbitMQ
 * delivers the return before the confirm, so both are known once the confirm arrives.
 */
@Component
public class CommandPublisher {

	private final RabbitTemplate rabbitTemplate;
	private final JsonMapper jsonMapper;
	private final long confirmTimeoutMs;

	public CommandPublisher(RabbitTemplate rabbitTemplate, JsonMapper jsonMapper, CommandProperties properties) {
		this.rabbitTemplate = rabbitTemplate;
		this.jsonMapper = jsonMapper;
		this.confirmTimeoutMs = properties.confirmTimeout().toMillis();
	}

	/** Returns once the broker has confirmed the command and routed it to a queue. */
	public void publish(CommandMessageDto command) throws CommandPublishException {
		String routingKey = RabbitConfig.COMMAND_ROUTING_KEY_PREFIX + command.droneId();
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(command.commandId().toString());
		Message message = new Message(jsonMapper.writeValueAsBytes(command), properties);
		CorrelationData correlation = new CorrelationData(command.commandId().toString());

		CorrelationData.Confirm confirm;
		try {
			rabbitTemplate.send(RabbitConfig.COMMAND_EXCHANGE, routingKey, message, correlation);
			confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (AmqpException e) {
			throw new CommandPublishException("RabbitMQ is not reachable: " + e.getMessage(), e);
		} catch (TimeoutException e) {
			throw new CommandPublishException("no publisher confirm within " + confirmTimeoutMs + " ms", e);
		} catch (ExecutionException e) {
			throw new CommandPublishException("publishing failed: " + e.getCause(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CommandPublishException("interrupted while waiting for the publisher confirm", e);
		}

		if (!confirm.ack()) {
			throw new CommandPublishException("RabbitMQ refused the command (nack): " + confirm.reason());
		}
		if (correlation.getReturned() != null) {
			throw new CommandPublishException("no queue is bound for " + routingKey
					+ " (is the edge adapter running?)");
		}
	}

}
