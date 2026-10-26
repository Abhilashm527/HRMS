package com.ivoyant.hrms.common.web;

/** One field-level problem, listed under {@code errors} in a validation problem response. */
public record ApiError(String field, String message) {
}
