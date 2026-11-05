package com.otonomc3.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the command channel, under {@code otonom.command.*}.
 *
 * @param resultTimeout  a command still PENDING this long after it was issued becomes TIMEOUT
 * @param confirmTimeout how long issuing a command waits for the broker's publisher confirm
 */
@ConfigurationProperties("otonom.command")
public record CommandProperties(
		@DefaultValue("12s") Duration resultTimeout,
		@DefaultValue("5s") Duration confirmTimeout) {
}
