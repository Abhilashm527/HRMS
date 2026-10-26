package com.ivoyant.hrms.common.event;

import java.util.List;

/** Lookup lists of a settings module were saved. */
public record ConfigListsChangedEvent(String module, List<String> listKeys) {
}
