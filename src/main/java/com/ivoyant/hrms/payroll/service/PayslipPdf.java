package com.ivoyant.hrms.payroll.service;

import com.ivoyant.hrms.payroll.service.PayrollEngine.Line;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One-page A4 payslip and yearly tax statement, drawn directly in PDF with the built-in Helvetica fonts. */
final class PayslipPdf {

	private PayslipPdf() {
	}

	@SuppressWarnings("unchecked")
	static byte[] render(Map<String, Object> s, String org) {
		Page p = new Page();
		Map<String, Object> e = (Map<String, Object>) s.get("employee");
		p.text(56, 790, 16, true, org.isBlank() ? "Payslip" : org);
		p.text(56, 770, 11, false, "Payslip for " + s.get("label"));
		p.rule(760);
		String[][] info = {{"Employee", (String) e.get("name"), "Code", (String) e.get("code")}, {"Title", (String) e.get("title"), "Department", (String) e.get("department")},
				{"PAN", nz(s.get("pan")), "UAN", nz(s.get("uan"))}, {"Bank", nz(s.get("bank")), "Pay date", String.valueOf(s.get("payDate"))},
				{"Paid days", s.get("paidDays") + " of " + s.get("daysInMonth"), "Loss of pay", plain((BigDecimal) s.get("lopDays")) + plain((BigDecimal) s.get("extraLop"), " + ")}};
		float y = 742;
		for (String[] r : info) {
			p.text(56, y, 9, false, r[0]);
			p.text(130, y, 10, true, r[1]);
			p.text(320, y, 9, false, r[2]);
			p.text(400, y, 10, true, r[3]);
			y -= 16;
		}
		y -= 6;
		p.rule(y + 6);
		p.text(56, y - 10, 11, true, "Earnings");
		p.text(320, y - 10, 11, true, "Deductions");
		List<Line> earn = (List<Line>) s.get("earnings");
		List<Line> ded = ((List<Line>) s.get("deductions")).stream().filter(l -> l.amount().signum() > 0).toList();
		float ly = y - 30;
		for (int i = 0; i < Math.max(earn.size(), ded.size()); i++) {
			if (i < earn.size()) {
				p.text(56, ly, 10, false, earn.get(i).label());
				p.right(290, ly, 10, false, inr(earn.get(i).amount()));
			}
			if (i < ded.size()) {
				p.text(320, ly, 10, false, ded.get(i).label());
				p.right(540, ly, 10, false, inr(ded.get(i).amount()));
			}
			ly -= 16;
		}
		ly -= 4;
		p.rule(ly + 10);
		p.text(56, ly - 4, 10, true, "Gross earnings");
		p.right(290, ly - 4, 10, true, inr((BigDecimal) s.get("gross")));
		p.text(320, ly - 4, 10, true, "Total deductions");
		p.right(540, ly - 4, 10, true, inr((BigDecimal) s.get("totalDeductions")));
		p.rule(ly - 16);
		p.text(56, ly - 40, 13, true, "Net pay");
		p.right(540, ly - 40, 13, true, inr((BigDecimal) s.get("net")));
		List<Line> er = (List<Line>) s.get("employer");
		String erTxt = er.stream().filter(l -> l.amount().signum() > 0).map(l -> l.label() + " " + inr(l.amount())).reduce((a, b) -> a + "  ·  " + b).orElse("");
		if (!erTxt.isEmpty()) {
			p.text(56, ly - 64, 9, false, "Paid by the company on top: " + erTxt);
		}
		p.text(56, 60, 8, false, "Computer-generated payslip; no signature needed. Tax regime: " + ("OLD".equals(s.get("regime")) ? "old" : "new")
				+ ". Income tax is an estimate spread over the year.");
		return p.pdf();
	}

	static byte[] statement(Map<String, Object> a, String fy, String org) {
		Page p = new Page();
		p.text(56, 790, 16, true, org.isBlank() ? "Tax statement" : org);
		p.text(56, 770, 11, false, "Annual pay and tax statement · FY " + fy);
		p.rule(760);
		String[][] rows = {{"Employee", (String) a.get("name")}, {"Code", (String) a.get("code")}, {"PAN", nz(a.get("pan"))},
				{"Months paid", String.valueOf(a.get("months"))}, {"Gross salary", inr((BigDecimal) a.get("gross"))}, {"Provident fund (employee)", inr((BigDecimal) a.get("pf"))},
				{"Professional tax", inr((BigDecimal) a.get("pt"))}, {"Income tax deducted (TDS)", inr((BigDecimal) a.get("tds"))}, {"Net pay", inr((BigDecimal) a.get("net"))}};
		float y = 736;
		for (String[] r : rows) {
			p.text(56, y, 10, false, r[0]);
			p.right(540, y, 10, true, r[1]);
			y -= 20;
		}
		p.text(56, y - 20, 8, false, "A summary from payroll for your records. It is not Form 16; the employer issues Form 16 from TRACES after filing returns.");
		return p.pdf();
	}

	private static String inr(BigDecimal v) {
		NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
		f.setMaximumFractionDigits(0);
		return "Rs. " + f.format(v == null ? BigDecimal.ZERO : v);
	}

	private static String plain(BigDecimal v) {
		return v == null ? "0" : v.stripTrailingZeros().toPlainString();
	}

	private static String plain(BigDecimal v, String prefix) {
		return v == null || v.signum() == 0 ? "" : prefix + plain(v);
	}

	private static String nz(Object o) {
		return o == null ? "—" : o.toString();
	}

	/** A single page of text and lines. */
	private static final class Page {
		private final StringBuilder c = new StringBuilder();

		void text(float x, float y, int size, boolean bold, String s) {
			c.append("BT /").append(bold ? "F2" : "F1").append(' ').append(size).append(" Tf ").append(f(x)).append(' ').append(f(y)).append(" Td (").append(esc(s))
					.append(") Tj ET\n");
		}

		/** Right-aligned at x, using Helvetica's average width (good enough for numbers). */
		void right(float x, float y, int size, boolean bold, String s) {
			float w = clean(s).length() * size * (bold ? 0.56f : 0.52f);
			text(x - w, y, size, bold, s);
		}

		void rule(float y) {
			c.append("0.8 0.82 0.86 RG 0.6 w 56 ").append(f(y)).append(" m 540 ").append(f(y)).append(" l S\n");
		}

		byte[] pdf() {
			byte[] stream = c.toString().getBytes(StandardCharsets.ISO_8859_1);
			List<String> objs = new ArrayList<>(List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
					"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>", "",
					"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
					"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			List<Integer> off = new ArrayList<>();
			w(out, "%PDF-1.4\n");
			for (int i = 0; i < objs.size(); i++) {
				off.add(out.size());
				w(out, (i + 1) + " 0 obj\n");
				if (i == 3) {
					w(out, "<< /Length " + stream.length + " >>\nstream\n");
					out.writeBytes(stream);
					w(out, "\nendstream");
				}
				else {
					w(out, objs.get(i));
				}
				w(out, "\nendobj\n");
			}
			int x = out.size();
			w(out, "xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
			off.forEach(o -> w(out, String.format("%010d 00000 n \n", o)));
			w(out, "trailer\n<< /Size " + (objs.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + x + "\n%%EOF\n");
			return out.toByteArray();
		}

		private static void w(ByteArrayOutputStream o, String s) {
			o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
		}

		private static String f(float v) {
			return String.format(Locale.ROOT, "%.1f", v);
		}

		private static String esc(String s) {
			return clean(s).replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
		}

		private static String clean(String s) {
			String t = (s == null ? "" : s).replace("₹", "Rs. ").replace('–', '-').replace('—', '-').replace('·', '-').replace("•", "*");
			StringBuilder b = new StringBuilder();
			for (char ch : t.toCharArray()) {
				b.append(ch < 256 ? ch : '?');
			}
			return b.toString();
		}
	}
}
