package com.ivoyant.hrms.documents.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.documents.service.DocumentService;
import com.ivoyant.hrms.documents.service.DocumentService.FileContent;
import com.ivoyant.hrms.documents.service.DocumentService.Upload;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Documents: shared folders and files. What each person sees follows the folder's group; see {@link DocumentService}. */
@RestController
@RequestMapping("/api/v1/documents")
@RequiresMember
public class DocumentController {

	private final DocumentService docs;

	public DocumentController(DocumentService docs) {
		this.docs = docs;
	}

	@GetMapping("/tree")
	public Map<String, Object> tree() {
		return docs.tree();
	}

	/** {@code view} = starred or recent; otherwise the folder ({@code folderId}, default the top folder). */
	@GetMapping("/items")
	public Map<String, Object> items(@RequestParam(required = false) UUID folderId, @RequestParam(required = false) String view,
			@RequestParam(required = false) String q) {
		return docs.items(folderId, view, q);
	}

	@PostMapping("/folders")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> createFolder(@RequestBody Map<String, Object> body) {
		Object p = body.get("parentId");
		return docs.createFolder(p == null ? null : UUID.fromString(p.toString()), body.get("name") == null ? null : body.get("name").toString());
	}

	@PatchMapping("/folders/{id}")
	public Map<String, Object> updateFolder(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return docs.updateFolder(id, body);
	}

	@DeleteMapping("/folders/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFolder(@PathVariable UUID id) {
		docs.deleteFolder(id);
	}

	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> upload(@RequestParam(required = false) UUID folderId, @RequestParam("files") List<MultipartFile> files) throws IOException {
		List<Upload> up = new ArrayList<>();
		for (MultipartFile f : files) {
			up.add(new Upload(f.getOriginalFilename(), f.getBytes()));
		}
		return docs.upload(folderId, up);
	}

	@PatchMapping("/files/{id}")
	public Map<String, Object> updateFile(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return docs.updateFile(id, body);
	}

	@DeleteMapping("/files/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteFile(@PathVariable UUID id) {
		docs.deleteFile(id);
	}

	/** Opens in the browser when {@code inline=true} (PDFs and images), otherwise downloads. */
	@GetMapping("/files/{id}/content")
	public ResponseEntity<byte[]> content(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean inline) {
		FileContent f = docs.content(id);
		boolean show = inline && (f.mime().startsWith("image/") || f.mime().equals("application/pdf"));
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, (show ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(f.name(),
						java.nio.charset.StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(f.bytes());
	}

	/** Body: {@code {ids: [...], targetId}}. */
	@PostMapping("/move")
	public Map<String, Object> move(@RequestBody Map<String, Object> body) {
		return docs.move(ids(body), UUID.fromString(body.get("targetId").toString()));
	}

	/** Body: {@code {ids: [...]}}; folders go with everything inside them. */
	@PostMapping("/delete")
	public Map<String, Object> delete(@RequestBody Map<String, Object> body) {
		return docs.delete(ids(body));
	}

	@PutMapping("/star/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void star(@PathVariable UUID id) {
		docs.star(id, true);
	}

	@DeleteMapping("/star/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unstar(@PathVariable UUID id) {
		docs.star(id, false);
	}

	private static List<UUID> ids(Map<String, Object> body) {
		Object v = body.get("ids");
		if (!(v instanceof List<?> l)) {
			return List.of();
		}
		return l.stream().map(x -> UUID.fromString(x.toString())).toList();
	}
}
