package com.ivoyant.hrms.documents;

import java.util.List;
import java.util.Map;

/** Documents as the global search sees them: only folders and files the caller may open. */
public interface DocumentSearch {

	/** Each hit: {@code id, kind (FOLDER|FILE), name, where, folderId}. */
	List<Map<String, Object>> search(String q, int limit);
}
