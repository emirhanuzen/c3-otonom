package com.otonomc3.core.geofence.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GeofenceRepository extends JpaRepository<GeofenceEntity, Long> {

	/**
	 * The condition of an effective zone in SQL, for the alias {@code g} and a
	 * timestamptz parameter {@code :at}. GeofenceEntity.isEffective is the same rule in Java.
	 */
	String EFFECTIVE = "g.active AND (g.valid_from IS NULL OR g.valid_from <= :at)"
			+ " AND (g.valid_until IS NULL OR g.valid_until > :at)";

	List<GeofenceEntity> findAllByOrderByIdAsc();

	/** Ids of the zones effective at {@code at}, ordered. */
	@Query(value = "SELECT g.id FROM geofence g WHERE " + EFFECTIVE + " ORDER BY g.id", nativeQuery = true)
	List<Long> findEffectiveIds(@Param("at") Instant at);

	/** @return 0 when the geofence does not exist */
	@Transactional
	@Modifying
	@Query("DELETE FROM GeofenceEntity g WHERE g.id = :id")
	int deleteGeofence(@Param("id") long id);

	/**
	 * Lets PostGIS judge a polygon before it is stored.
	 *
	 * @param wkt the polygon as WKT, x = longitude, y = latitude
	 * @return one row: valid (boolean), reason (text, "Valid Geometry" when valid)
	 *         and area in m² on the WGS84 spheroid (null when the polygon is not valid)
	 */
	@Query(value = """
			SELECT ST_IsValid(g) AS valid,
			       ST_IsValidReason(g) AS reason,
			       CASE WHEN ST_IsValid(g) THEN ST_Area(CAST(g AS geography)) END AS area
			FROM (SELECT ST_GeomFromText(:wkt, 4326) AS g) AS s
			""", nativeQuery = true)
	List<Object[]> checkPolygon(@Param("wkt") String wkt);

}
