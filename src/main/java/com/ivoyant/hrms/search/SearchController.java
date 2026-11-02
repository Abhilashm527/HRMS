package com.ivoyant.hrms.search;

import com.ivoyant.hrms.common.authz.RequiresMember;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The top bar's search. {@code group} narrows to one kind of result; {@code limit} caps each group. */
@RestController
@RequestMapping("/api/v1/search")
@RequiresMember
public class SearchController {

	private final SearchService search;

	public SearchController(SearchService search) {
		this.search = search;
	}

	@GetMapping
	public Map<String, Object> search(@RequestParam String q, @RequestParam(required = false) String group, @RequestParam(defaultValue = "6") int limit) {
		return search.search(q, group, limit);
	}
}
