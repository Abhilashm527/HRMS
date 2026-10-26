package com.ivoyant.hrms.onboarding.api;

import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.onboarding.service.PortalService;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The new-hire portal. No sign-in: the invite link's token travels in the {@code X-Onboarding-Token} header. */
@RestController
@RequestMapping("/api/v1/portal/onboarding")
public class PortalController {

	private static final String TOKEN = "X-Onboarding-Token";

	private final PortalService portal;
	private final PortalRateLimiter limiter;

	public PortalController(PortalService portal, PortalRateLimiter limiter) {
		this.portal = portal;
		this.limiter = limiter;
	}

	@GetMapping
	public Map<String, Object> view(@RequestHeader(TOKEN) String token) {
		limiter.request(token);
		return portal.view(token);
	}

	@PatchMapping("/details")
	public Map<String, Object> details(@RequestHeader(TOKEN) String token, @RequestBody Map<String, Object> body) {
		limiter.request(token);
		return portal.patchDetails(token, body);
	}

	@PostMapping("/documents/{docId}")
	public Map<String, Object> upload(@RequestHeader(TOKEN) String token, @PathVariable UUID docId, @RequestParam("file") MultipartFile file) {
		limiter.upload(token);
		try {
			return portal.upload(token, docId, file.getOriginalFilename(), file.getContentType(), file.getBytes());
		}
		catch (IOException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("file", "UNREADABLE", "The file could not be read")));
		}
	}

	@PostMapping("/submit")
	public Map<String, Object> submit(@RequestHeader(TOKEN) String token) {
		limiter.request(token);
		return portal.submit(token);
	}
}
