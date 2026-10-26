package com.ivoyant.hrms.org.domain;

import java.io.Serializable;
import java.util.UUID;

public record SetupAckId(UUID orgId, String key) implements Serializable {
}
