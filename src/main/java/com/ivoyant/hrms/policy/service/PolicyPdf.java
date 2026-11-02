package com.ivoyant.hrms.policy.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A policy as an A4 PDF in Helvetica: title, a version line, then each section's heading and paragraphs, over as many pages as it
 * takes, with page numbers. Characters outside Latin-1 are replaced with close ASCII equivalents (the built-in fonts can't show them).
 */
final class PolicyPdf {

	record Section(String heading, String body) {
	}

	private static final float TOP = 780;
	private static final float BOTTOM = 70;

	private PolicyPdf() {
	}

	static byte[] render(String title, String subtitle, List<Section> sections, String footer) {
		List<StringBuilder> pages = new ArrayList<>();
		StringBuilder page = new StringBuilder();
		pages.add(page);
		float y = TOP;
		y = text(page, title, 18, true, y);
		y = text(page, subtitle, 10, false, y - 2) - 14;
		for (Section s : sections) {
			// keep a heading with at least its first lines
			if (y < BOTTOM + 60) {
				page = new StringBuilder();
				pages.add(page);
				y = TOP;
			}
			y = text(page, s.heading(), 13, true, y) - 2;
			for (String para : (s.body() == null ? "" : s.body()).split("\\n")) {
				if (para.isBlank()) {
					y -= 6;
					continue;
				}
				for (String line : wrap(clean(para.strip()), 92)) {
					if (y < BOTTOM) {
						page = new StringBuilder();
						pages.add(page);
						y = TOP;
					}
					y = line(page, line, 11, false, y);
				}
				y -= 6;
			}
			y -= 10;
		}
		for (int i = 0; i < pages.size(); i++) {
			String foot = clean(footer) + "   ·   Page " + (i + 1) + " of " + pages.size();
			pages.get(i).append("BT /F1 8 Tf 64 40 Td (").append(escape(clean(foot))).append(") Tj ET\n");
		}
		return assemble(pages);
	}

	private static float text(StringBuilder page, String t, int size, boolean bold, float y) {
		for (String part : wrap(clean(t == null ? "" : t), size <= 11 ? 92 : size <= 13 ? 76 : 56)) {
			y = line(page, part, size, bold, y);
		}
		return y;
	}

	private static float line(StringBuilder page, String s, int size, boolean bold, float y) {
		page.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size).append(" Tf 64 ").append(String.format("%.1f", y)).append(" Td (")
				.append(escape(s)).append(") Tj ET\n");
		return y - (size + 5);
	}

	private static byte[] assemble(List<StringBuilder> pages) {
		int n = pages.size();
		// 1 catalog, 2 pages, 3 font, 4 bold font, then for each page: page object and its content stream
		List<byte[]> objects = new ArrayList<>();
		StringBuilder kids = new StringBuilder();
		for (int i = 0; i < n; i++) {
			kids.append(5 + i * 2).append(" 0 R ");
		}
		objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
		objects.add(bytes("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + n + " >>"));
		objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"));
		objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
		for (int i = 0; i < n; i++) {
			int contentId = 6 + i * 2;
			objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents " + contentId
					+ " 0 R /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> >>"));
			byte[] stream = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
			ByteArrayOutputStream s = new ByteArrayOutputStream();
			s.writeBytes(bytes("<< /Length " + stream.length + " >>\nstream\n"));
			s.writeBytes(stream);
			s.writeBytes(bytes("\nendstream"));
			objects.add(s.toByteArray());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		out.writeBytes(bytes("%PDF-1.4\n"));
		for (int i = 0; i < objects.size(); i++) {
			offsets.add(out.size());
			out.writeBytes(bytes((i + 1) + " 0 obj\n"));
			out.writeBytes(objects.get(i));
			out.writeBytes(bytes("\nendobj\n"));
		}
		int xref = out.size();
		out.writeBytes(bytes("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n"));
		for (int o : offsets) {
			out.writeBytes(bytes(String.format("%010d 00000 n \n", o)));
		}
		out.writeBytes(bytes("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n"));
		return out.toByteArray();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	static String clean(String s) {
		String t = s.replace('–', '-').replace('—', '-').replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
				.replace("₹", "Rs. ").replace("…", "...").replace("−", "-");
		StringBuilder b = new StringBuilder();
		for (char c : t.toCharArray()) {
			b.append(c < 256 ? c : '?');
		}
		return b.toString();
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
	}

	static List<String> wrap(String text, int width) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String w : text.split(" ")) {
			if (line.length() > 0 && line.length() + 1 + w.length() > width) {
				out.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0) {
				line.append(' ');
			}
			line.append(w);
		}
		if (line.length() > 0) {
			out.add(line.toString());
		}
		return out;
	}
}
