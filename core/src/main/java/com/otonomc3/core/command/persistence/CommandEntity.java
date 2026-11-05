package com.otonomc3.core.command.persistence;

import java.time.Instant;
import java.util.UUID;

import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;
import org.springframework.data.domain.Persistable;

/** One issued command; the schema is in db/migration/V3__create_command.sql. */
@Entity
@Table(name = "command")
@Getter
@Setter
@NoArgsConstructor
public class CommandEntity implements Persistable<UUID> {

	/** The contract's commandId, assigned by the core before saving. */
	@Id
	@Column(name = "id", nullable = false)
	private UUID id;

	@Column(name = "drone_id", nullable = false)
	private Integer droneId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 32)
	private CommandType type;

	/**
	 * The validated params as a JSON object. Kept as text in Java and cast to jsonb
	 * on write, so Hibernate needs no JSON library of its own.
	 */
	@Column(name = "params", nullable = false, columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	private String params;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private CommandStatus status;

	@Column(name = "detail", columnDefinition = "text")
	private String detail;

	@Column(name = "issued_at", nullable = false, columnDefinition = "timestamptz")
	private Instant issuedAt;

	@Column(name = "completed_at", columnDefinition = "timestamptz")
	private Instant completedAt;

	/** Who issued the command; always null until users are introduced. */
	@Column(name = "issued_by", columnDefinition = "text")
	private String issuedBy;

	// The id is assigned, so Spring Data cannot tell a new entity from a stored one
	// by a null id; without this, save() would SELECT before every INSERT.
	@Transient
	private boolean isNew = true;

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

}
