package com.ivoyant.hrms.reports.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Turns a report's columns and rows into a CSV, XLSX, PDF or TXT file. No external libraries: XLSX and PDF are written directly. */
final class ReportFiles {

	private ReportFiles() {
	}

	static String mime(String fmt) {
		return switch (fmt) {
			case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
			case "PDF" -> "application/pdf";
			case "TXT" -> "text/plain;charset=UTF-8";
			default -> "text/csv;charset=UTF-8";
		};
	}

	static byte[] write(String fmt, String key, String title, String subtitle, List<String> labels, List<Boolean> numeric, List<List<Object>> rows) {
		return switch (fmt) {
			case "XLSX" -> xlsx(labels, numeric, rows);
			case "PDF" -> pdf(title, subtitle, labels, numeric, rows);
			case "TXT" -> txt(key, labels, rows);
			default -> csv(labels, rows);
		};
	}

	static String text(Object v) {
		if (v == null) {
			return "";
		}
		if (v instanceof BigDecimal b) {
			return b.stripTrailingZeros().toPlainString();
		}
		return v.toString();
	}

	// ------------------------------------------------------------------ CSV and TXT

	private static byte[] csv(List<String> labels, List<List<Object>> rows) {
		StringBuilder b = new StringBuilder("﻿");
		b.append(String.join(",", labels.stream().map(ReportFiles::cell).toList())).append("\r\n");
		for (List<Object> r : rows) {
			b.append(String.join(",", r.stream().map(v -> cell(text(v))).toList())).append("\r\n");
		}
		return b.toString().getBytes(StandardCharsets.UTF_8);
	}

	/** Quotes when needed and defuses spreadsheet formulas. */
	private static String cell(String s) {
		String v = !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?") ? "'" + s : s;
		return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
	}

	/** The EPFO ECR file is #~# separated with no header; other text files are tab separated with one. */
	private static byte[] txt(String key, List<String> labels, List<List<Object>> rows) {
		StringBuilder b = new StringBuilder();
		boolean ecr = "ecr".equals(key);
		String sep = ecr ? "#~#" : "\t";
		if (!ecr) {
			b.append(String.join(sep, labels)).append("\r\n");
		}
		for (List<Object> r : rows) {
			b.append(String.join(sep, r.stream().map(v -> text(v).replace("\t", " ").replace("\r", " ").replace("\n", " ").replace("#~#", " ")).toList())).append("\r\n");
		}
		return b.toString().getBytes(StandardCharsets.UTF_8);
	}

	// ------------------------------------------------------------------ XLSX

	private static byte[] xlsx(List<String> labels, List<Boolean> numeric, List<List<Object>> rows) {
		StringBuilder sh = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
				.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\">")
				.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
		for (int c = 0; c < labels.size(); c++) {
			int w = labels.get(c).length();
			for (int i = 0; i < Math.min(rows.size(), 200); i++) {
				w = Math.max(w, text(rows.get(i).get(c)).length());
			}
			sh.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"").append(Math.min(60, w + 3)).append("\" customWidth=\"1\"/>");
		}
		sh.append("</cols><sheetData>");
		row(sh, 1, new ArrayList<>(labels), null, true);
		int n = 2;
		for (List<Object> r : rows) {
			row(sh, n++, r, numeric, false);
		}
		sh.append("</sheetData></worksheet>");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(out)) {
			put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
					+ "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
					+ "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
					+ "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
					+ "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
			put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
					+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
			put(z, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
					+ "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Report\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
			put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
					+ "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
					+ "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
			put(z, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
					+ "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
					+ "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
					+ "<borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
					+ "<cellXfs count=\"2\"><xf fontId=\"0\"/><xf fontId=\"1\" applyFont=\"1\"/></cellXfs></styleSheet>");
			put(z, "xl/worksheets/sheet1.xml", sh.toString());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return out.toByteArray();
	}

	private static void row(StringBuilder b, int n, List<Object> r, List<Boolean> numeric, boolean head) {
		b.append("<row r=\"").append(n).append("\">");
		for (int c = 0; c < r.size(); c++) {
			Object v = r.get(c);
			String ref = col(c) + n;
			if (v == null) {
				continue;
			}
			boolean num = !head && numeric != null && numeric.get(c) && (v instanceof Number);
			if (num) {
				b.append("<c r=\"").append(ref).append("\"><v>").append(text(v)).append("</v></c>");
			}
			else {
				b.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(head ? " s=\"1\"" : "").append("><is><t xml:space=\"preserve\">").append(xml(text(v)))
						.append("</t></is></c>");
			}
		}
		b.append("</row>");
	}

	private static String col(int i) {
		StringBuilder s = new StringBuilder();
		for (int n = i + 1; n > 0; n = (n - 1) / 26) {
			s.insert(0, (char) ('A' + (n - 1) % 26));
		}
		return s.toString();
	}

	private static String xml(String s) {
		StringBuilder b = new StringBuilder();
		for (char ch : s.toCharArray()) {
			switch (ch) {
				case '&' -> b.append("&amp;");
				case '<' -> b.append("&lt;");
				case '>' -> b.append("&gt;");
				case '"' -> b.append("&quot;");
				default -> {
					if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') {
						b.append(ch);
					}
				}
			}
		}
		return b.toString();
	}

	private static void put(ZipOutputStream z, String name, String body) throws IOException {
		z.putNextEntry(new ZipEntry(name));
		z.write(body.getBytes(StandardCharsets.UTF_8));
		z.closeEntry();
	}

	// ------------------------------------------------------------------ PDF (landscape A4 table, paged)

	private static byte[] pdf(String title, String subtitle, List<String> labels, List<Boolean> numeric, List<List<Object>> rows) {
		float left = 36, right = 806, top = 555, bottom = 40, rowH = 13;
		int cols = labels.size();
		float[] want = new float[cols];
		for (int c = 0; c < cols; c++) {
			int w = labels.get(c).length();
			for (int i = 0; i < Math.min(rows.size(), 300); i++) {
				w = Math.max(w, clean(text(rows.get(i).get(c))).length());
			}
			want[c] = Math.min(40, Math.max(4, w));
		}
		float total = 0;
		for (float w : want) {
			total += w;
		}
		float[] x = new float[cols + 1];
		x[0] = left;
		for (int c = 0; c < cols; c++) {
			x[c + 1] = x[c] + (right - left) * want[c] / total;
		}
		List<StringBuilder> pages = new ArrayList<>();
		StringBuilder pg = null;
		float y = 0;
		int i = 0;
		do {
			if (pg == null || y < bottom + rowH) {
				pg = new StringBuilder();
				pages.add(pg);
				y = top;
				if (pages.size() == 1) {
					t(pg, left, y, 14, true, title);
					y -= 16;
					t(pg, left, y, 9, false, subtitle);
					y -= 18;
				}
				pg.append("0.95 0.96 0.97 rg ").append(f(left)).append(' ').append(f(y - 4)).append(' ').append(f(right - left)).append(' ').append(f(rowH)).append(" re f 0 g\n");
				for (int c = 0; c < cols; c++) {
					cellText(pg, x[c], x[c + 1], y, true, numeric.get(c), labels.get(c));
				}
				y -= rowH;
			}
			if (i < rows.size()) {
				List<Object> r = rows.get(i);
				for (int c = 0; c < cols; c++) {
					cellText(pg, x[c], x[c + 1], y, false, numeric.get(c), text(r.get(c)));
				}
				pg.append("0.88 0.89 0.91 RG 0.4 w ").append(f(left)).append(' ').append(f(y - 4)).append(" m ").append(f(right)).append(' ').append(f(y - 4)).append(" l S\n");
				y -= rowH;
			}
			i++;
		}
		while (i < rows.size());
		if (rows.isEmpty()) {
			t(pg, left, y - 6, 9, false, "No rows for these filters.");
		}
		for (int p = 0; p < pages.size(); p++) {
			t(pages.get(p), left, 20, 7, false, "Page " + (p + 1) + " of " + pages.size());
		}
		return assemble(pages);
	}

	private static void cellText(StringBuilder pg, float x0, float x1, float y, boolean bold, boolean right, String s) {
		int size = 7;
		float room = x1 - x0 - 6;
		String v = clean(s);
		int max = (int) (room / (size * 0.5f));
		if (v.length() > max) {
			v = v.substring(0, Math.max(0, max - 2)) + "..";
		}
		float w = v.length() * size * 0.5f;
		t(pg, right ? x1 - 3 - w : x0 + 3, y, size, bold, v);
	}

	private static void t(StringBuilder pg, float x, float y, int size, boolean bold, String s) {
		pg.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size).append(" Tf ").append(f(x)).append(' ').append(f(y)).append(" Td (")
				.append(clean(s).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj ET\n");
	}

	private static String clean(String s) {
		String v = (s == null ? "" : s).replace("₹", "Rs.").replace('–', '-').replace('—', '-').replace('·', '-').replace('→', '>');
		StringBuilder b = new StringBuilder();
		for (char ch : v.toCharArray()) {
			b.append(ch < 256 && ch >= 0x20 ? ch : ch == '\n' ? ' ' : '?');
		}
		return b.toString();
	}

	private static String f(float v) {
		return String.format(Locale.ROOT, "%.1f", v);
	}

	private static byte[] assemble(List<StringBuilder> pages) {
		int n = pages.size();
		// objects: 1 catalog, 2 pages, 3 font, 4 bold font, then (page, content) pairs
		List<byte[]> objs = new ArrayList<>();
		StringBuilder kids = new StringBuilder();
		for (int p = 0; p < n; p++) {
			kids.append(5 + p * 2).append(" 0 R ");
		}
		objs.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
		objs.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>").getBytes(StandardCharsets.ISO_8859_1));
		objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
		objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
		for (int p = 0; p < n; p++) {
			objs.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 842 595] /Contents " + (6 + p * 2) + " 0 R /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> >>")
					.getBytes(StandardCharsets.ISO_8859_1));
			byte[] stream = pages.get(p).toString().getBytes(StandardCharsets.ISO_8859_1);
			ByteArrayOutputStream c = new ByteArrayOutputStream();
			c.writeBytes(("<< /Length " + stream.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
			c.writeBytes(stream);
			c.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
			objs.add(c.toByteArray());
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
		List<Integer> off = new ArrayList<>();
		for (int i = 0; i < objs.size(); i++) {
			off.add(out.size());
			out.writeBytes(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
			out.writeBytes(objs.get(i));
			out.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
		}
		int xref = out.size();
		StringBuilder t = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
		off.forEach(o -> t.append(String.format("%010d 00000 n \n", o)));
		t.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
		out.writeBytes(t.toString().getBytes(StandardCharsets.ISO_8859_1));
		return out.toByteArray();
	}
}
