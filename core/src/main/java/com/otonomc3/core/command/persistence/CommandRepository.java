package com.otonomc3.core.command.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The request thread (publish failure), the result listener and the timeout job
 * may all finish the same command, so a status change is a conditional UPDATE:
 * it only happens when the command is still in one of the expected states, and
 * the returned row count tells the caller whether it won.
 */
public interface CommandRepository extends JpaRepository<CommandEntity, UUID> {

	List<CommandEntity> findByDroneIdOrderByIssuedAtDescIdDesc(Integer droneId, Limit limit);

	/** The drone's newest command of one of these types. */
	Optional<CommandEntity> findFirstByDroneIdAndTypeInOrderByIssuedAtDescIdDesc(Integer droneId,
			Collection<CommandType> types);

	@Query("SELECT c.id FROM CommandEntity c WHERE c.status = :status AND c.issuedAt < :issuedBefore")
	List<UUID> findIdsByStatusIssuedBefore(@Param("status") CommandStatus status,
			@Param("issuedBefore") Instant issuedBefore);

	@Transactional
	@Modifying
	@Query("""
			UPDATE CommandEntity c SET c.status = :status, c.detail = :detail, c.completedAt = :completedAt
			WHERE c.id = :id AND c.status IN :from
			""")
	int complete(@Param("id") UUID id, @Param("status") CommandStatus status, @Param("detail") String detail,
			@Param("completedAt") Instant completedAt, @Param("from") Collection<CommandStatus> from);

}
