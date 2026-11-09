package com.otonomc3.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the route planning settings; the planner lives in com.otonomc3.core.route. */
@Configuration
@EnableConfigurationProperties(RouteProperties.class)
public class RouteConfig {
}
