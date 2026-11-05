package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the alarm settings; the engine and the rules live in com.otonomc3.core.alarm. */
@Configuration
@EnableConfigurationProperties(AlarmProperties.class)
public class AlarmConfig {
}
