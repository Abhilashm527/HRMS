package com.ivoyant.hrms.common.web;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Every body also carries {@code code} and {@code message}
 * (plus {@code breaks} / {@code errors} where relevant), the shape the settings UI expects.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> ErrorItem.field(e.getField(), "VALIDATION", e.getDefaultMessage()))
				.toList();
		ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "Validation failed",
				"Validation failed");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, HttpStatus.UNPROCESSABLE_ENTITY, request);
	}

	/** Framework errors (malformed JSON, wrong method, ...) get the same code/message members as ours. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail pd && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
			pd.setProperty("code", statusCode.value() == 400 ? "BAD_REQUEST" : HttpStatus.valueOf(statusCode.value()).name());
			pd.setProperty("message", pd.getDetail());
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	@ExceptionHandler(BadRequestException.class)
	ProblemDetail badRequest(BadRequestException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad request", ex.getMessage());
		if (!ex.getDetails().isEmpty()) {
			problem.setProperty("details", ex.getDetails());
		}
		return problem;
	}

	@ExceptionHandler(UnprocessableException.class)
	ProblemDetail unprocessable(UnprocessableException ex) {
		ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "Validation failed",
				ex.getMessage());
		problem.setProperty("errors", ex.getErrors());
		return problem;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	ProblemDetail conflict(ConflictException ex) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, ex instanceof CodedConflictException c ? c.getCode() : "CONFLICT",
				"Conflict", ex.getMessage());
		if (!ex.getDetails().isEmpty()) {
			problem.setProperty("details", ex.getDetails());
		}
		return problem;
	}

	@ExceptionHandler(OrgArchivedException.class)
	ProblemDetail archived(OrgArchivedException ex) {
		return problem(HttpStatus.valueOf(423), "ORG_ARCHIVED", "Organization archived", ex.getMessage());
	}

	@ExceptionHandler(InUseException.class)
	ProblemDetail inUse(InUseException ex) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "IN_USE", "In use", ex.getMessage());
		if (!ex.getUsage().isEmpty()) {
			problem.setProperty("usage", ex.getUsage());
		}
		return problem;
	}

	@ExceptionHandler(ConfirmRequiredException.class)
	ProblemDetail confirmRequired(ConfirmRequiredException ex) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "CONFIRM_REQUIRED", "Confirmation required",
				ex.getMessage());
		problem.setProperty("breaks", ex.getBreaks());
		return problem;
	}

	@ExceptionHandler(StatusException.class)
	ProblemDetail status(StatusException ex) {
		ProblemDetail problem = problem(ex.getStatus(), ex.getCode(), ex.getStatus().getReasonPhrase(), ex.getMessage());
		ex.getExtra().forEach(problem::setProperty);
		return problem;
	}

	@ExceptionHandler(StaleException.class)
	ProblemDetail stale(StaleException ex) {
		return problem(HttpStatus.CONFLICT, "STALE", "Conflict", ex.getMessage());
	}

	@ExceptionHandler({ForbiddenException.class, AuthorizationDeniedException.class})
	ProblemDetail forbidden(RuntimeException ex) {
		return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden", ex.getMessage());
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail optimisticLock(ObjectOptimisticLockingFailureException ex) {
		return problem(HttpStatus.CONFLICT, "STALE", "Conflict",
				"The record was changed by someone else. Reload it and try again.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail integrity(DataIntegrityViolationException ex) {
		return problem(HttpStatus.CONFLICT, "CONFLICT", "Conflict", "The change violates a data constraint.");
	}

	private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setProperty("code", code);
		problem.setProperty("message", detail);
		return problem;
	}
}
