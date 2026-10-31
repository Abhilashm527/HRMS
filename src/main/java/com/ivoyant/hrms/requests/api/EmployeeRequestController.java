package com.ivoyant.hrms.requests.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.requests.service.EmployeeRequestService;
import com.ivoyant.hrms.requests.service.EmployeeRequestService.FileContent;
import com.ivoyant.hrms.requests.service.TaxDeclarationService;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Employee requests: letters, reimbursements and helpdesk tickets from self-service, the queues that act on them (Employee requests),
 * and tax declarations. Who may act on what is decided in the services.
 */
@RestController
@RequestMapping("/api/v1/requests")
@RequiresMember
public class EmployeeRequestController {

	private final EmployeeRequestService requests;
	private final TaxDeclarationService tax;

	public EmployeeRequestController(EmployeeRequestService requests, TaxDeclarationService tax) {
		this.requests = requests;
		this.tax = tax;
	}

	/** My requests; {@code kind} = LETTER, REIMBURSEMENT or HELPDESK. */
	@GetMapping("/mine")
	public Map<String, Object> mine(@RequestParam(required = false) String kind) {
		return requests.mine(kind);
	}

	/** Multipart: kind, typeId, title (tickets), note, amount and expenseDate (claims), file (the receipt). */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> submit(@RequestParam String kind, @RequestParam(required = false) UUID typeId, @RequestParam(required = false) String title,
			@RequestParam(required = false) String note, @RequestParam(required = false) String amount, @RequestParam(required = false) String expenseDate,
			@RequestParam(required = false) MultipartFile file) throws IOException {
		return requests.submit(kind, typeId, title, note, amount, expenseDate, file == null ? null : file.getOriginalFilename(),
				file == null ? null : file.getBytes());
	}

	/**
	 * A queue: {@code queue} = LETTER, REIMBURSEMENT, HELPDESK or DECLARATION; {@code closed=true} for decided ones. Counts cover all
	 * queues.
	 */
	@GetMapping("/inbox")
	public Map<String, Object> inbox(@RequestParam(defaultValue = "LETTER") String queue, @RequestParam(defaultValue = "false") boolean closed) {
		boolean decl = "DECLARATION".equalsIgnoreCase(queue);
		Map<String, Object> out = new LinkedHashMap<>(requests.inbox(decl ? "LETTER" : queue, closed));
		@SuppressWarnings("unchecked")
		Map<String, Long> counts = new LinkedHashMap<>((Map<String, Long>) out.get("counts"));
		counts.put("DECLARATION", tax.pendingCount());
		out.put("counts", counts);
		out.put("canVerifyTax", tax.canVerifyTax());
		if (decl) {
			out.put("items", tax.queue(closed));
		}
		return out;
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable UUID id) {
		return requests.detail(id);
	}

	@PostMapping("/{id}/withdraw")
	public Map<String, Object> withdraw(@PathVariable UUID id) {
		return requests.withdraw(id);
	}

	/** Manager approval (to Finance) or Finance payout approval. Body: {@code note}. */
	@PostMapping("/{id}/approve")
	public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return requests.approve(id, note(body));
	}

	@PostMapping("/{id}/reject")
	public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return requests.reject(id, note(body));
	}

	@PostMapping("/{id}/resolve")
	public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return requests.resolve(id, note(body));
	}

	/** Multipart: note and, optionally, file (HR's own signed PDF); without a file a standard letter is generated. */
	@PostMapping(value = "/{id}/issue", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> issue(@PathVariable UUID id, @RequestParam(required = false) String note, @RequestParam(required = false) MultipartFile file)
			throws IOException {
		return requests.issue(id, note, file == null ? null : file.getOriginalFilename(), file == null ? null : file.getBytes());
	}

	@GetMapping("/{id}/letter-preview")
	public ResponseEntity<byte[]> preview(@PathVariable UUID id) {
		return send(new FileContent(requests.preview(id), "application/pdf", "letter-preview.pdf"), true);
	}

	/** {@code which} = receipt or letter. */
	@GetMapping("/{id}/files/{which}")
	public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable String which) {
		return send(requests.file(id, which), false);
	}

	private static ResponseEntity<byte[]> send(FileContent f, boolean inline) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(f.name()).build().toString())
				.header("X-Content-Type-Options", "nosniff").body(f.bytes());
	}

	// ---- tax declaration

	@GetMapping("/tax-declaration")
	public Map<String, Object> myTax() {
		return tax.mine();
	}

	/** Body: regime (NEW or OLD), sec80c, sec80d, rentMonthly, nps, homeLoan, submit. */
	@PutMapping("/tax-declaration")
	public Map<String, Object> saveTax(@RequestBody Map<String, Object> body) {
		return tax.save(body);
	}

	@PostMapping("/tax-declarations/{id}/verify")
	public Map<String, Object> verify(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return tax.decide(id, true, note(body));
	}

	@PostMapping("/tax-declarations/{id}/reject")
	public Map<String, Object> rejectTax(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return tax.decide(id, false, note(body));
	}

	private static String note(Map<String, Object> body) {
		return body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
	}
}
