package com.ivoyant.hrms.assets.api;

import com.ivoyant.hrms.assets.service.AssetExtractService;
import com.ivoyant.hrms.assets.service.AssetImportService;
import com.ivoyant.hrms.common.web.StatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Import and "Let AI fill this in" for both registers. Needs Edit on the assets module. */
@RestController
@RequestMapping("/api/v1/assets")
public class AssetToolsController {

	private static final String EDIT = "@moduleAccess.has('assets', 'EDIT')";

	private final AssetExtractService extract;
	private final AssetImportService importer;

	public AssetToolsController(AssetExtractService extract, AssetImportService importer) {
		this.extract = extract;
		this.importer = importer;
	}

	/** {@code {kind: HARDWARE|SOFTWARE, text}}. Nothing is saved. */
	@PostMapping("/extract")
	@PreAuthorize(EDIT)
	public Map<String, Object> extract(@RequestBody Map<String, Object> body) {
		return extract.extract(String.valueOf(body.get("kind")), body.get("text") == null ? null : body.get("text").toString());
	}

	/** The same from an uploaded .txt, .csv, .eml or .json file (up to 2 MB). */
	@PostMapping(value = "/extract/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize(EDIT)
	public Map<String, Object> extractFile(@RequestParam String kind, @RequestParam("file") MultipartFile file) {
		if (file.getSize() > 2 * 1024 * 1024) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File is larger than 2 MB");
		}
		try {
			return extract.extract(kind, new String(file.getBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}

	@GetMapping("/{kind}/import/template")
	@PreAuthorize(EDIT)
	public ResponseEntity<byte[]> template(@PathVariable String kind) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(kind + "-import-template.csv").build().toString())
				.body(importer.template(kind).getBytes(StandardCharsets.UTF_8));
	}

	@PostMapping("/{kind}/import")
	@PreAuthorize(EDIT)
	public Map<String, Object> importCsv(@PathVariable String kind, @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
		if (!kind.equalsIgnoreCase("hardware") && !kind.equalsIgnoreCase("software")) {
			throw new StatusException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Import hardware or software");
		}
		try {
			return importer.run(kind, new String(file.getBytes(), StandardCharsets.UTF_8), dryRun);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}
}
