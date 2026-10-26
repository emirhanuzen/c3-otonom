package com.otonomc3.core.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology and listener settings of the telemetry consumer.
 *
 * <p>The declarables below are declared by Spring's RabbitAdmin as soon as the
 * listener opens its connection at startup.
 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties.class)
public class RabbitConfig {

	/** Also declared by the edge adapter (publisher.py); both declarations must be identical. */
	public static final String TELEMETRY_EXCHANGE = "otonom.telemetry";
	public static final String TELEMETRY_QUEUE = "core.telemetry";
	public static final String TELEMETRY_ROUTING_PATTERN = "telemetry.#";

	public static final String DEAD_LETTER_EXCHANGE = "otonom.dlx";
	public static final String TELEMETRY_DLQ = "core.telemetry.dlq";
	/** Routing key of dead telemetry messages on the DLX, whether rejected by the broker or by our listener. */
	public static final String TELEMETRY_DLQ_ROUTING_KEY = TELEMETRY_DLQ;

	public static final String TELEMETRY_CONTAINER_FACTORY = "telemetryContainerFactory";

	@Bean
	TopicExchange telemetryExchange() {
		// durable, not auto-delete, no arguments: same as the edge adapter's exchange_declare().
		return new TopicExchange(TELEMETRY_EXCHANGE, true, false);
	}

	@Bean
	Queue telemetryQueue() {
		return QueueBuilder.durable(TELEMETRY_QUEUE)
				.deadLetterExchange(DEAD_LETTER_EXCHANGE)
				.deadLetterRoutingKey(TELEMETRY_DLQ_ROUTING_KEY)
				.build();
	}

	@Bean
	Binding telemetryBinding(Queue telemetryQueue, TopicExchange telemetryExchange) {
		return BindingBuilder.bind(telemetryQueue).to(telemetryExchange).with(TELEMETRY_ROUTING_PATTERN);
	}

	@Bean
	DirectExchange deadLetterExchange() {
		return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
	}

	@Bean
	Queue telemetryDeadLetterQueue() {
		return QueueBuilder.durable(TELEMETRY_DLQ).build();
	}

	@Bean
	Binding telemetryDeadLetterBinding(Queue telemetryDeadLetterQueue, DirectExchange deadLetterExchange) {
		return BindingBuilder.bind(telemetryDeadLetterQueue).to(deadLetterExchange).with(TELEMETRY_DLQ_ROUTING_KEY);
	}

	/**
	 * Batch listener: the container collects up to batchSize messages, or whatever
	 * arrived within batchTimeout, and hands them over as one List. With AUTO ack
	 * the whole batch is acked after the listener returns, and requeued when it throws.
	 */
	@Bean(TELEMETRY_CONTAINER_FACTORY)
	SimpleRabbitListenerContainerFactory telemetryContainerFactory(ConnectionFactory connectionFactory,
			TelemetryProperties properties) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
		factory.setDefaultRequeueRejected(true);
		factory.setPrefetchCount(properties.prefetch());
		// One consumer keeps messages in queue order, which the seq gap detection relies on.
		factory.setConcurrentConsumers(1);
		factory.setMaxConcurrentConsumers(1);
		factory.setBatchListener(true);
		factory.setConsumerBatchEnabled(true);
		factory.setBatchSize(properties.batchSize());
		factory.setContainerCustomizer(
				container -> container.setBatchReceiveTimeout(properties.batchTimeout().toMillis()));
		return factory;
	}

}
