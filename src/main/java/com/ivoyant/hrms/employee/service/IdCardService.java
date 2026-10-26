package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.Positions;
import com.ivoyant.hrms.org.Titles;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The ID card as data (for the preview) and as a CR80 PDF (54 × 86 mm). */
@Service
public class IdCardService {

	private final EmployeeRepository employees;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final FieldConfig fieldConfig;
	private final OrgDirectory org;
	private final Positions positions;
	private final Titles titles;
	private final PhotoService photos;

	IdCardService(EmployeeRepository employees, CurrentUser currentUser, EmployeeActions actions, FieldConfig fieldConfig, OrgDirectory org,
			Positions positions, Titles titles, PhotoService photos) {
		this.employees = employees;
		this.currentUser = currentUser;
		this.actions = actions;
		this.fieldConfig = fieldConfig;
		this.org = org;
		this.positions = positions;
		this.titles = titles;
		this.photos = photos;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> card(UUID id) {
		Employee e = employees.findByIdAndOrgId(id, currentUser.orgId()).filter(x -> x.getArchivedAt() == null)
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
		actions.require("print_idcard", id);
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", e.getDisplayName());
		m.put("employeeId", e.getEmployeeCode());
		var position = e.getDesignationId() == null ? null : positions.get(e.getDesignationId()).orElse(null);
		m.put("positionName", position == null ? null : position.name());
		m.put("grade", position == null ? null : position.grade());
		m.put("title", e.getTitleId() == null ? null : titles.names(List.of(e.getTitleId())).get(e.getTitleId()));
		m.put("department", e.getDepartmentId() == null ? null : org.departmentNames(List.of(e.getDepartmentId())).get(e.getDepartmentId()));
		m.put("location", e.getLocationId() == null ? null : org.locationNames(List.of(e.getLocationId())).get(e.getLocationId()));
		m.put("joined", e.getJoiningDate());
		FieldAccess phone = fieldConfig.levels(EmployeeFields.AREA, id).getOrDefault("phone", FieldAccess.HIDDEN);
		m.put("phone", phone.atLeast(FieldAccess.VIEW) ? e.getPhone() : null);
		m.put("photoUrl", ProfileAssembler.photoUrl(e));
		m.put("validTill", "Mar " + (java.time.Year.now().getValue() + 2));
		return m;
	}

	@Transactional(readOnly = true)
	public byte[] pdf(UUID id) {
		Map<String, Object> c = card(id);
		return render(c, photos.card(id));
	}

	// ---- a one-page PDF written by hand: two fonts, optional JPEG photo ----

	private static byte[] render(Map<String, Object> c, byte[] jpeg) {
		StringBuilder s = new StringBuilder();
		s.append("0.145 0.388 0.922 rg 0 214 153 30 re f\n");
		s.append("BT /F2 11 Tf 1 1 1 rg 10 225 Td (").append(esc("IVOYANT")).append(") Tj ET\n");
		if (jpeg != null) {
			s.append("q 70 0 0 70 41.5 130 cm /Im1 Do Q\n");
		}
		else {
			s.append("0.9 0.9 0.9 rg 41.5 130 70 70 re f\n");
		}
		centered(s, "F2", 11, 112, str(c.get("name")));
		centered(s, "F1", 8, 100, str(c.get("positionName")) + (c.get("grade") == null ? "" : " (" + c.get("grade") + ")"));
		centered(s, "F1", 8, 89, str(c.get("title")));
		centered(s, "F1", 8, 78, str(c.get("department")) + (c.get("location") == null ? "" : " · " + c.get("location")));
		centered(s, "F1", 7, 62, "Joined " + (c.get("joined") == null ? "" : ((java.time.LocalDate) c.get("joined")).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))));
		if (c.get("phone") != null) {
			centered(s, "F1", 7, 52, str(c.get("phone")));
		}
		centered(s, "F2", 10, 32, str(c.get("employeeId")));
		centered(s, "F1", 6, 14, "Valid till " + c.get("validTill"));
		byte[] content = s.toString().getBytes(StandardCharsets.ISO_8859_1);

		List<byte[]> objs = new ArrayList<>();
		objs.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
		objs.add(bytes("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"));
		objs.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 153 244] /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >>"
				+ (jpeg != null ? " /XObject << /Im1 7 0 R >>" : "") + " >> >>"));
		objs.add(stream("<< /Length " + content.length + " >>", content));
		objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
		objs.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
		if (jpeg != null) {
			objs.add(stream("<< /Type /XObject /Subtype /Image /Width 256 /Height 256 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length " + jpeg.length + " >>", jpeg));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, "%PDF-1.4\n");
		int[] offsets = new int[objs.size()];
		for (int i = 0; i < objs.size(); i++) {
			offsets[i] = out.size();
			write(out, (i + 1) + " 0 obj\n");
			out.writeBytes(objs.get(i));
			write(out, "\nendobj\n");
		}
		int xref = out.size();
		write(out, "xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
		for (int off : offsets) {
			write(out, String.format("%010d 00000 n \n", off));
		}
		write(out, "trailer\n<< /Size " + (objs.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
		return out.toByteArray();
	}

	private static void centered(StringBuilder s, String font, int size, int y, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		double width = text.length() * size * 0.5;
		double x = Math.max(4, (153 - width) / 2);
		s.append("BT /").append(font).append(' ').append(size).append(" Tf 0.1 0.1 0.1 rg ").append(String.format(Locale.ROOT, "%.1f", x)).append(' ').append(y)
				.append(" Td (").append(esc(text)).append(") Tj ET\n");
	}

	private static String esc(String t) {
		StringBuilder b = new StringBuilder();
		for (char ch : t.toCharArray()) {
			if (ch == '(' || ch == ')' || ch == '\\') {
				b.append('\\').append(ch);
			}
			else if (ch > 255) {
				b.append(ch == '·' ? '-' : '?');
			}
			else {
				b.append(ch);
			}
		}
		return b.toString();
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] stream(String dict, byte[] data) {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		write(o, dict + "\nstream\n");
		o.writeBytes(data);
		write(o, "\nendstream");
		return o.toByteArray();
	}

	private static void write(ByteArrayOutputStream o, String s) {
		o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
	}
}
