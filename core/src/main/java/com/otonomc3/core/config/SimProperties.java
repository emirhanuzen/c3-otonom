package com.otonomc3.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Simulation settings, under {@code otonom.sim.*}.
 *
 * @param enabled true when the fleet is ArduPilot SITL, so the FAULT_* commands that change
 *                simulator parameters may be issued; set by the "sim" profile (application-sim.properties)
 */
@ConfigurationProperties("otonom.sim")
public record SimProperties(@DefaultValue("false") boolean enabled) {
}
