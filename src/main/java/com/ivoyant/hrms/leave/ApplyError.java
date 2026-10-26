package com.ivoyant.hrms.leave;

/** One thing wrong with a leave application. */
public record ApplyError(String field, String code, String message) {
}
