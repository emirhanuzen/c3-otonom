package com.otonomc3.core.alarm.persistence;

import java.time.Instant;
import java.util.List;

import com.otonomc3.core.alarm.rule.AlarmSeverity;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engine (scheduler thread) and the API (request threads) change the same
 * rows, so changes are made with targeted UPDATE statements that only touch their
 * own columns. A load-modify-save of the whole entity could undo the other's change,
 * for example an acknowledgement made between the load and the save.
 */
public interface AlarmRepository extends JpaRepository<AlarmEntity, Long> {

	List<AlarmEntity> findByClearedAtIsNullOrderByRaisedAtDescIdDesc(Limit limit);

	List<AlarmEntity> findByClearedAtIsNotNullOrderByRaisedAtDescIdDesc(Limit limit);

	List<AlarmEntity> findByDroneIdAndClearedAtIsNullOrderByRaisedAtDescIdDesc(Integer droneId, Limit limit);

	List<AlarmEntity> findByDroneIdAndClearedAtIsNotNullOrderByRaisedAtDescIdDesc(Integer droneId, Limit limit);

	@Transactional
	@Modifying
	@Query("""
			UPDATE AlarmEntity a SET a.severity = :severity, a.message = :message, a.updatedAt = :at
			WHERE a.id = :id
			""")
	int updateSeverity(@Param("id") long id, @Param("severity") AlarmSeverity severity,
			@Param("message") String message, @Param("at") Instant at);

	/** Like {@link #updateSeverity}, and also removes the acknowledgement so the alarm must be acknowledged again. */
	@Transactional
	@Modifying
	@Query("""
			UPDATE AlarmEntity a SET a.severity = :severity, a.message = :message, a.updatedAt = :at,
				a.acknowledgedAt = NULL, a.acknowledgedBy = NULL
			WHERE a.id = :id
			""")
	int escalate(@Param("id") long id, @Param("severity") AlarmSeverity severity,
			@Param("message") String message, @Param("at") Instant at);

	@Transactional
	@Modifying
	@Query("UPDATE AlarmEntity a SET a.clearedAt = :at WHERE a.id = :id AND a.clearedAt IS NULL")
	int clear(@Param("id") long id, @Param("at") Instant at);

	/** @return 0 when the alarm does not exist or is already acknowledged */
	@Transactional
	@Modifying
	@Query("UPDATE AlarmEntity a SET a.acknowledgedAt = :at WHERE a.id = :id AND a.acknowledgedAt IS NULL")
	int acknowledge(@Param("id") long id, @Param("at") Instant at);

	/** Clears every alarm that is still active; used at startup. */
	@Transactional
	@Modifying
	@Query("UPDATE AlarmEntity a SET a.clearedAt = :at WHERE a.clearedAt IS NULL")
	int clearAllActive(@Param("at") Instant at);

}
