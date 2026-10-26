package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile photos: a square crop kept at 64, 256 and 512 pixels; the initials on the department colour until there is one. */
@Service
public class PhotoService {

	public static final int MAX_BYTES = 5 * 1024 * 1024;
	private static final int[] SIZES = {64, 256, 512};
	private static final String[] COLORS = {"#2563eb", "#7c3aed", "#db2777", "#ea580c", "#16a34a", "#0891b2", "#ca8a04", "#4b5563"};

	public record Image(byte[] bytes, String mime, int version) {
	}

	private final EmployeeRepository employees;
	private final FileStore store;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final Audit audit;

	PhotoService(EmployeeRepository employees, FileStore store, CurrentUser currentUser, EmployeeActions actions, Audit audit) {
		this.employees = employees;
		this.store = store;
		this.currentUser = currentUser;
		this.actions = actions;
		this.audit = audit;
	}

	private Employee find(UUID id) {
		return employees.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
	}

	private void requireChange(UUID id) {
		if (!id.equals(currentUser.employeeId()) && !actions.can("update_employee", id)) {
			throw new ForbiddenException("You cannot change this photo");
		}
	}

	@Transactional
	public Map<String, Object> upload(UUID id, byte[] bytes) {
		Employee e = find(id);
		requireChange(id);
		if (bytes == null || bytes.length == 0) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Choose an image file");
		}
		if (bytes.length > MAX_BYTES) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Photo is larger than 5 MB");
		}
		BufferedImage src;
		try {
			src = ImageIO.read(new ByteArrayInputStream(bytes));
		}
		catch (IOException ex) {
			src = null;
		}
		if (src == null) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Choose a JPEG or PNG image");
		}
		int side = Math.min(src.getWidth(), src.getHeight());
		BufferedImage square = src.getSubimage((src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
		int version = e.getPhotoVersion() + 1;
		String base = currentUser.orgId() + "/employee/" + id + "/photo/" + version;
		try {
			for (int size : SIZES) {
				store.put(base + "/" + size + ".jpg", jpeg(square, size));
			}
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The photo could not be saved. Try again.");
		}
		e.setPhotoKey(base);
		e.setPhotoVersion(version);
		employees.saveAndFlush(e);
		audit.publish("employee", "Employee", id, "PHOTO", null, Map.of("version", version));
		return Map.of("photoUrl", "/api/v1/employees/" + id + "/photo?s=256&v=" + version, "version", version);
	}

	@Transactional
	public void reset(UUID id) {
		Employee e = find(id);
		requireChange(id);
		if (e.getPhotoKey() == null) {
			return;
		}
		e.setPhotoKey(null);
		e.setPhotoVersion(e.getPhotoVersion() + 1);
		employees.saveAndFlush(e);
		audit.publish("employee", "Employee", id, "PHOTO_RESET", null, null);
	}

	/** The photo at 64, 256 or 512 pixels, or the initials badge. */
	@Transactional(readOnly = true)
	public Image get(UUID id, int size) {
		Employee e = find(id);
		actions.require("view_directory", null);
		int s = size <= 64 ? 64 : size <= 256 ? 256 : 512;
		if (e.getPhotoKey() != null) {
			try {
				return new Image(store.get(e.getPhotoKey() + "/" + s + ".jpg"), "image/jpeg", e.getPhotoVersion());
			}
			catch (IOException ex) {
				// fall through to the initials badge
			}
		}
		String initials = ProfileAssembler.initials(e);
		String color = COLORS[Math.floorMod(e.getDepartmentId() == null ? 0 : e.getDepartmentId().hashCode(), COLORS.length)];
		String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='" + s + "' height='" + s + "' viewBox='0 0 100 100'><rect width='100' height='100' fill='" + color
				+ "'/><text x='50' y='50' dy='.35em' text-anchor='middle' font-family='sans-serif' font-size='40' fill='#fff'>" + initials + "</text></svg>";
		return new Image(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml", e.getPhotoVersion());
	}

	/** The 256-pixel JPEG for the ID card, or null when the person has no photo. */
	byte[] card(UUID id) {
		Employee e = find(id);
		if (e.getPhotoKey() == null) {
			return null;
		}
		try {
			return store.get(e.getPhotoKey() + "/256.jpg");
		}
		catch (IOException ex) {
			return null;
		}
	}

	private static byte[] jpeg(BufferedImage square, int size) throws IOException {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(square, 0, 0, size, size, java.awt.Color.WHITE, null);
		g.dispose();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ImageIO.write(out, "jpg", bos);
		return bos.toByteArray();
	}
}
