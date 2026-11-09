package com.otonomc3.core.mission.service;

import com.otonomc3.core.mission.dto.MissionValidationDto;

/** A mission that crosses or has points in no-fly zones cannot be assigned; answered with 409 and its legs. */
public class MissionBlockedException extends RuntimeException {

	private final MissionValidationDto validation;

	public MissionBlockedException(MissionValidationDto validation) {
		super("Görev yasak bölgelerden geçiyor; önce düzeltilmeli");
		this.validation = validation;
	}

	public MissionValidationDto getValidation() {
		return validation;
	}

}
