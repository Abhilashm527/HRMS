package com.ivoyant.hrms.requests.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A one-page A4 PDF of plain paragraphs in Helvetica, enough for a standard HR letter. Long paragraphs are wrapped; characters
 * outside Latin-1 are replaced with close ASCII equivalents (the built-in fonts can't show them).
 */
final class LetterPdf {

	/** A line of the letter: text, size and whether it is bold. Blank text adds space. */
	record Line(String text, int size, boolean bold) {

		static Line p(String t) {
			return new Line(t, 11, false);
		}

		static Line b(String t) {
			return new Line(t, 11, true);
		}

		static Line h(String t) {
			return new Line(t, 15, true);
		}

		static Line gap() {
			return new Line("", 11, false);
		}
	}

	private LetterPdf() {
	}

	static byte[] render(List<Line> lines) {
		StringBuilder content = new StringBuilder();
		float y = 780;
		for (Line l : lines) {
			if (l.text().isEmpty()) {
				y -= 10;
				continue;
			}
			for (String part : wrap(clean(l.text()), l.size() <= 11 ? 92 : 64)) {
				if (y < 60) {
					break;
				}
				content.append("BT /").append(l.bold() ? "F2" : "F1").append(' ').append(l.size()).append(" Tf 64 ").append(String.format("%.1f", y))
						.append(" Td (").append(escape(part)).append(") Tj ET\n");
				y -= l.size() + 6;
			}
			y -= 4;
		}
		byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
		List<String> objects = new ArrayList<>();
		objects.add("<< /Type /Catalog /Pages 2 0 R >>");
		objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
		objects.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>");
		objects.add(null); // the content stream, written below
		objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
		objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		List<Integer> offsets = new ArrayList<>();
		write(out, "%PDF-1.4\n");
		for (int i = 0; i < objects.size(); i++) {
			offsets.add(out.size());
			write(out, (i + 1) + " 0 obj\n");
			if (objects.get(i) == null) {
				write(out, "<< /Length " + stream.length + " >>\nstream\n");
				out.writeBytes(stream);
				write(out, "\nendstream");
			}
			else {
				write(out, objects.get(i));
			}
			write(out, "\nendobj\n");
		}
		int xref = out.size();
		write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
		for (int o : offsets) {
			write(out, String.format("%010d 00000 n \n", o));
		}
		write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, String s) {
		out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
	}

	static String clean(String s) {
		String t = s.replace('·', '-').replace('–', '-').replace('—', '-').replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
				.replace("₹", "Rs. ").replace("…", "...");
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
