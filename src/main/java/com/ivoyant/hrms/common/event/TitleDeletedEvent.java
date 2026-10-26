package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See TITLES_BACKEND_GUIDE.md section 10. */
public record TitleDeletedEvent(UUID id, UUID mergedInto) {
}
