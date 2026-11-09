package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the landing settings; the planner lives in com.otonomc3.core.landing. */
@Configuration
@EnableConfigurationProperties(LandingProperties.class)
public class LandingConfig {
}
