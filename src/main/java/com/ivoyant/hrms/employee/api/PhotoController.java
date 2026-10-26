package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.employee.service.IdCardService;
import com.ivoyant.hrms.employee.service.PhotoService;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Photo and ID card of an employee. */
@RestController
@RequestMapping("/api/v1/employees/{id}")
public class PhotoController {

	private final PhotoService photos;
	private final IdCardService idCards;

	public PhotoController(PhotoService photos, IdCardService idCards) {
		this.photos = photos;
		this.idCards = idCards;
	}

	@GetMapping("/photo")
	@RequiresMember
	public ResponseEntity<byte[]> photo(@PathVariable UUID id, @RequestParam(name = "s", defaultValue = "256") int size) {
		PhotoService.Image image = photos.get(id, size);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mime())).cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
				.header("X-Content-Type-Options", "nosniff").body(image.bytes());
	}

	@PutMapping("/photo")
	@RequiresMember
	public Map<String, Object> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
		try {
			return photos.upload(id, file.getBytes());
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}

	@DeleteMapping("/photo")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void reset(@PathVariable UUID id) {
		photos.reset(id);
	}

	@GetMapping("/id-card")
	@RequiresMember
	public Map<String, Object> card(@PathVariable UUID id) {
		return idCards.card(id);
	}

	@GetMapping("/id-card.pdf")
	@RequiresMember
	public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
		byte[] pdf = idCards.pdf(id);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("id-card.pdf").build().toString()).body(pdf);
	}
}
