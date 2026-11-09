package com.otonomc3.core.route.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;

import com.otonomc3.core.config.RouteProperties;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.springframework.stereotype.Component;

/**
 * Plans a route around the no-fly zones, in a local metric plane centred on the start
 * ({@link LocalProjection}).
 *
 * <p>Each zone becomes an obstacle: widened by otonom.route.clearance-m, simplified with a
 * tolerance of {@link #SIMPLIFY_RATIO} of the clearance (so at least 90% of the clearance is
 * kept), its own holes filled. Overlapping obstacles are merged; a pocket enclosed by several
 * of them stays free, so a target in it is unreachable (NO_ROUTE). Then:
 * <ol>
 * <li>a target inside (or on) an obstacle is refused, then a start inside one;</li>
 * <li>a straight line that touches no obstacle is the route;</li>
 * <li>otherwise A* (heuristic: straight distance to the target) runs on the visibility graph
 * whose nodes are the start, the target and the obstacles' vertices (outer and pocket rings), and whose edges are the
 * segments that do not pass through any obstacle. A segment may run along an obstacle's
 * boundary: it is tested against the obstacles shrunk by {@link #EDGE_TOLERANCE_M}.</li>
 * </ol>
 * The planner is pure computation; the zones come from the caller.
 */
@Component
public class RoutePlanner {

	/** Simplification tolerance as a share of the clearance. */
	static final double SIMPLIFY_RATIO = 0.1;
	/** A visibility edge may come this close (m) to the inside of an obstacle, so boundary edges count as free. */
	static final double EDGE_TOLERANCE_M = 0.5;
	/** Segments per quarter circle of the widened corners. */
	static final int QUADRANT_SEGMENTS = 4;

	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	private final double clearanceM;

	public RoutePlanner(RouteProperties properties) {
		this.clearanceM = properties.clearanceM();
	}

	/**
	 * @throws RouteBlockedException TARGET_IN_ZONE, START_IN_ZONE or NO_ROUTE
	 */
	public RoutePlan plan(double startLat, double startLon, double targetLat, double targetLon,
			List<RouteObstacle> zones) {
		LocalProjection projection = new LocalProjection(startLat, startLon);
		Coordinate start = projection.toLocal(startLat, startLon);
		Coordinate target = projection.toLocal(targetLat, targetLon);
		Point startPoint = GEOMETRY.createPoint(start);
		Point targetPoint = GEOMETRY.createPoint(target);

		List<Named> widened = zones.stream().map(zone -> widen(zone, projection)).toList();
		List<String> aroundTarget = names(widened, zone -> zone.area().covers(targetPoint));
		if (!aroundTarget.isEmpty()) {
			throw new RouteBlockedException(RouteBlockedException.Reason.TARGET_IN_ZONE, aroundTarget);
		}
		List<String> aroundStart = names(widened, zone -> zone.area().covers(startPoint));
		if (!aroundStart.isEmpty()) {
			throw new RouteBlockedException(RouteBlockedException.Reason.START_IN_ZONE, aroundStart);
		}

		LineString straight = GEOMETRY.createLineString(new Coordinate[] { start, target });
		List<Named> crossed = widened.stream()
				.filter(zone -> zone.area().intersects(straight))
				.sorted(Comparator.comparingDouble(zone -> zone.area().distance(startPoint)))
				.toList();
		if (crossed.isEmpty()) {
			return new RoutePlan(true, List.of(latLon(projection, target)), start.distance(target), List.of());
		}

		List<Polygon> obstacles = merge(widened);
		List<Coordinate> path = shortestPath(start, target, obstacles);
		if (path == null) {
			throw new RouteBlockedException(RouteBlockedException.Reason.NO_ROUTE, List.of());
		}
		double distance = 0;
		List<List<Double>> waypoints = new ArrayList<>(path.size() - 1);
		for (int i = 1; i < path.size(); i++) {
			distance += path.get(i - 1).distance(path.get(i));
			waypoints.add(latLon(projection, path.get(i)));
		}
		return new RoutePlan(false, waypoints, distance, crossed.stream().map(Named::name).distinct().toList());
	}

	/**
	 * Checks the straight leg from start to target against the zones, in the same plane and with
	 * the same widened obstacles as {@link #plan}: a leg this calls clear is what plan() would fly
	 * as a direct route. Used by mission validation.
	 */
	public LegCheck checkLeg(double startLat, double startLon, double targetLat, double targetLon,
			List<RouteObstacle> zones) {
		LocalProjection projection = new LocalProjection(startLat, startLon);
		Coordinate start = projection.toLocal(startLat, startLon);
		Coordinate target = projection.toLocal(targetLat, targetLon);
		Point startPoint = GEOMETRY.createPoint(start);
		Point targetPoint = GEOMETRY.createPoint(target);
		List<Named> widened = zones.stream().map(zone -> widen(zone, projection)).toList();
		LineString straight = GEOMETRY.createLineString(new Coordinate[] { start, target });
		List<String> crossed = widened.stream()
				.filter(zone -> zone.area().intersects(straight))
				.sorted(Comparator.comparingDouble(zone -> zone.area().distance(startPoint)))
				.map(Named::name)
				.distinct()
				.toList();
		return new LegCheck(crossed, names(widened, zone -> zone.area().covers(startPoint)),
				names(widened, zone -> zone.area().covers(targetPoint)));
	}

	/** The zone in the local plane, widened by the clearance, simplified, without holes. */
	private Named widen(RouteObstacle zone, LocalProjection projection) {
		Coordinate[] shell = Arrays.stream(zone.area().getExteriorRing().getCoordinates())
				.map(projection::toLocal)
				.toArray(Coordinate[]::new);
		Polygon local = GEOMETRY.createPolygon(shell);
		Geometry buffered = local.buffer(clearanceM, QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND);
		Geometry simplified = TopologyPreservingSimplifier.simplify(buffered, clearanceM * SIMPLIFY_RATIO);
		return new Named(zone.name(), fillHoles(simplified));
	}

	/** Overlapping obstacles become one, so no vertex of one lies inside another. */
	private static List<Polygon> merge(List<Named> widened) {
		Geometry union = UnaryUnionOp.union(widened.stream().map(Named::area).toList());
		List<Polygon> polygons = new ArrayList<>();
		for (int i = 0; i < union.getNumGeometries(); i++) {
			if (union.getGeometryN(i) instanceof Polygon polygon && !polygon.isEmpty()) {
				polygons.add(polygon);
			}
		}
		return polygons;
	}

	/** The polygons of one widened zone with their exterior rings only: a hole in a zone is not flown into. */
	private static Geometry fillHoles(Geometry geometry) {
		List<Polygon> shells = new ArrayList<>();
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			if (geometry.getGeometryN(i) instanceof Polygon polygon && !polygon.isEmpty()) {
				shells.add(GEOMETRY.createPolygon(polygon.getExteriorRing().getCoordinates()));
			}
		}
		Geometry union = UnaryUnionOp.union(shells);
		return union == null ? GEOMETRY.createMultiPolygon() : union;
	}

	/** A* on the visibility graph; null when the target cannot be reached. */
	private static List<Coordinate> shortestPath(Coordinate start, Coordinate target, List<Polygon> obstacles) {
		List<Coordinate> nodes = new ArrayList<>();
		nodes.add(start);
		nodes.add(target);
		for (Polygon obstacle : obstacles) {
			addRing(nodes, obstacle.getExteriorRing().getCoordinates());
			for (int i = 0; i < obstacle.getNumInteriorRing(); i++) {
				addRing(nodes, obstacle.getInteriorRingN(i).getCoordinates());
			}
		}
		Geometry shrunk = GEOMETRY.createMultiPolygon(obstacles.toArray(Polygon[]::new)).buffer(-EDGE_TOLERANCE_M);
		PreparedGeometry blocked = PreparedGeometryFactory.prepare(shrunk);

		int count = nodes.size();
		double[] cost = new double[count];
		int[] previous = new int[count];
		boolean[] done = new boolean[count];
		Arrays.fill(cost, Double.POSITIVE_INFINITY);
		Arrays.fill(previous, -1);
		cost[0] = 0;
		PriorityQueue<double[]> open = new PriorityQueue<>(Comparator.comparingDouble(entry -> entry[1]));
		open.add(new double[] { 0, start.distance(target) });
		while (!open.isEmpty()) {
			int current = (int) open.poll()[0];
			if (done[current]) {
				continue;
			}
			if (current == 1) {
				return path(nodes, previous);
			}
			done[current] = true;
			for (int next = 0; next < count; next++) {
				if (done[next] || next == current) {
					continue;
				}
				double candidate = cost[current] + nodes.get(current).distance(nodes.get(next));
				if (candidate >= cost[next] || !visible(nodes.get(current), nodes.get(next), blocked)) {
					continue;
				}
				cost[next] = candidate;
				previous[next] = current;
				open.add(new double[] { next, candidate + nodes.get(next).distance(target) });
			}
		}
		return null;
	}

	private static void addRing(List<Coordinate> nodes, Coordinate[] ring) {
		nodes.addAll(Arrays.asList(ring).subList(0, ring.length - 1));
	}

	private static boolean visible(Coordinate from, Coordinate to, PreparedGeometry blocked) {
		return from.equals2D(to) || !blocked.intersects(GEOMETRY.createLineString(new Coordinate[] { from, to }));
	}

	private static List<Coordinate> path(List<Coordinate> nodes, int[] previous) {
		List<Coordinate> path = new ArrayList<>();
		for (int node = 1; node != -1; node = previous[node]) {
			path.addFirst(nodes.get(node));
		}
		return path;
	}

	private static List<Double> latLon(LocalProjection projection, Coordinate local) {
		return List.of(round7(projection.lat(local)), round7(projection.lon(local)));
	}

	private static double round7(double value) {
		return Math.round(value * 1e7) / 1e7;
	}

	private static List<String> names(List<Named> zones, Predicate<Named> test) {
		return zones.stream().filter(test).map(Named::name).distinct().toList();
	}

	private record Named(String name, Geometry area) {
	}

}
