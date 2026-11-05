package com.otonomc3.core.command.dto;

/** @param enabled true when FAULT_* simulator commands may be issued (otonom.sim.enabled) */
public record SimStatusDto(boolean enabled) {
}
