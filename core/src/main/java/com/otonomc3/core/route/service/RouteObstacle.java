package com.otonomc3.core.route.service;

import org.locationtech.jts.geom.Polygon;

/**
 * A no-fly zone the planner must go around, as stored: WGS84, x = longitude, y = latitude.
 * The planner widens it by otonom.route.clearance-m itself.
 */
public record RouteObstacle(String name, Polygon area) {
}
