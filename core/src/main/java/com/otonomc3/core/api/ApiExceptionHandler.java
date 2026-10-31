package com.otonomc3.core.api;

import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.telemetry.service.InvalidTelemetryQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns API errors into RFC 9457 problem details (application/problem+json).
 *
 * <p>The base class does the same for Spring MVC's own errors, such as an
 * unparseable from/to parameter (400) or an unknown path (404).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(DroneNotFoundException.class)
	ProblemDetail handleDroneNotFound(DroneNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Drone not found");
		problem.setProperty("droneId", e.getDroneId());
		return problem;
	}

	@ExceptionHandler(InvalidTelemetryQueryException.class)
	ProblemDetail handleInvalidQuery(InvalidTelemetryQueryException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid query");
		return problem;
	}

}
