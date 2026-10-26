package com.ivoyant.hrms.config;

import java.util.List;

/** What other modules may ask the config module about Fields &amp; forms areas. */
public interface ConfigAreas {

	boolean exists(String areaKey);

	List<String> keys();
}
