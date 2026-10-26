package com.ivoyant.hrms.iam;

import java.util.UUID;

/** A role as other modules see it. */
public record RoleRef(UUID id, String key, String name) {
}
