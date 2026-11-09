package com.otonomc3.core.geofence.service;

/**
 * Published by GeofenceService after the zones changed (created, updated, deleted, imported)
 * or a zone became effective or stopped being effective.
 */
public record GeofencesChangedEvent() {
}
