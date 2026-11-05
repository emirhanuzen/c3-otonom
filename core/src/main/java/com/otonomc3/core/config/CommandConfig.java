package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the command and simulation settings; the RabbitMQ topology is in {@link RabbitConfig}. */
@Configuration
@EnableConfigurationProperties({ CommandProperties.class, SimProperties.class })
public class CommandConfig {
}
