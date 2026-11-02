package com.ivoyant.hrms.policy.service;

import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.policy.service.PolicyService.Section;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;

/**
 * Turns an uploaded PDF or Word (.docx) file into policy sections. Headings become sections (PDF: larger or bold short lines; Word:
 * heading styles), with paragraphs, bullet and numbered lists, and (Word) tables. Repeating page headers, footers and page numbers
 * are dropped. Scanned PDFs have no text and are refused.
 */
final class PolicyImport {

	private PolicyImport() {
	}

	record Result(String title, List<Section> sections, int pages) {
	}

	static final long MAX_BYTES = 5L * 1024 * 1024;
	private static final Pattern BULLET = Pattern.compile("^\\s*([•●○◦▪▫■□\\-–—*·]|\\(?[0-9]{1,2}[.)]|\\(?[a-zA-Z][.)])\\s+(.*)$");
	private static final Pattern PAGE_NO = Pattern.compile("^(page\\s*)?\\d+(\\s*(of|/)\\s*\\d+)?$|\\bpage\\s*\\d+\\s*(of|/)\\s*\\d+\\s*$", Pattern.CASE_INSENSITIVE);

	static Result read(String fileName, byte[] bytes) {
		if (bytes.length == 0) {
			throw bad("The file is empty");
		}
		if (bytes.length > MAX_BYTES) {
			throw bad("Files can be up to 5 MB");
		}
		String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
		boolean pdf = bytes.length > 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
		boolean zip = bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K';
		Result r;
		if (pdf) {
			r = pdf(bytes);
		}
		else if (zip && (n.endsWith(".docx") || n.isEmpty())) {
			r = docx(bytes);
		}
		else if (n.endsWith(".doc")) {
			throw bad("Old .doc files can't be read; save it as .docx or PDF and upload again");
		}
		else {
			throw bad("Upload a PDF or a Word (.docx) file");
		}
		if (r.sections().isEmpty()) {
			throw bad(pdf ? "No text found in this PDF. It may be a scanned image; upload a text PDF or Word file" : "No text found in this file");
		}
		List<Section> clean = r.sections().stream().map(s -> new Section(cut(s.heading(), 120), PolicyHtml.clean(s.body()))).limit(60).toList();
		String title = r.title() == null || r.title().isBlank() ? baseName(fileName) : r.title();
		return new Result(cut(title, 120), clean, r.pages());
	}

	// ================================================================== PDF

	private record Line(String text, float size, boolean bold, float y, float gap, int page) {
	}

	private static Result pdf(byte[] bytes) {
		List<Line> lines = new ArrayList<>();
		int pages;
		try (PDDocument doc = Loader.loadPDF(bytes)) {
			if (doc.isEncrypted()) {
				throw bad("This PDF is password protected; remove the password and upload again");
			}
			pages = doc.getNumberOfPages();
			if (pages > 300) {
				throw bad("Keep it under 300 pages");
			}
			PDFTextStripper st = new PDFTextStripper() {
				private float lastY = -1;
				private int lastPage = -1;

				@Override
				protected void writeString(String text, List<TextPosition> pos) {
					if (text == null || text.isBlank() || pos.isEmpty()) {
						return;
					}
					float size = 0;
					int bold = 0;
					for (TextPosition p : pos) {
						size = Math.max(size, p.getFontSizeInPt());
						String f = p.getFont() == null || p.getFont().getName() == null ? "" : p.getFont().getName().toLowerCase(Locale.ROOT);
						if (f.contains("bold") || f.contains("black") || f.contains("heavy") || f.contains("semibold")) {
							bold++;
						}
					}
					float y = pos.get(0).getYDirAdj();
					int page = getCurrentPageNo();
					float gap = page != lastPage || lastY < 0 ? 999 : Math.max(0, y - lastY);
					lastY = y;
					lastPage = page;
					lines.add(new Line(text.strip(), Math.round(size * 2) / 2f, bold * 2 > pos.size(), y, gap, page));
				}
			};
			st.setSortByPosition(true);
			st.setSuppressDuplicateOverlappingText(true);
			st.getText(doc);
		}
		catch (IOException e) {
			throw bad("This PDF couldn't be read");
		}
		if (lines.isEmpty()) {
			return new Result(null, List.of(), pages);
		}
		// drop running headers/footers and page numbers: the same text on many pages, at the top or bottom
		Map<Integer, List<Line>> byPage = new LinkedHashMap<>();
		lines.forEach(l -> byPage.computeIfAbsent(l.page(), k -> new ArrayList<>()).add(l));
		java.util.Set<Line> edge = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		Map<String, java.util.Set<Integer>> edgePages = new HashMap<>();
		byPage.forEach((pg, ls) -> {
			for (int i = 0; i < ls.size(); i++) {
				if (i < 2 || i >= ls.size() - 2) {
					edge.add(ls.get(i));
					edgePages.computeIfAbsent(norm(ls.get(i).text()), k -> new java.util.HashSet<>()).add(pg);
				}
			}
		});
		int pageCount = Math.max(1, pages);
		List<Line> body = lines.stream().filter(l -> !(edge.contains(l) && (PAGE_NO.matcher(l.text()).find()
				|| (pageCount >= 2 && edgePages.get(norm(l.text())).size() >= Math.max(2, (pageCount + 1) / 2))))).toList();
		if (body.isEmpty()) {
			return new Result(null, List.of(), pages);
		}
		// the body size is the size most characters are set in
		Map<Float, Integer> chars = new HashMap<>();
		for (Line l : body) {
			chars.merge(l.size(), l.text().length(), Integer::sum);
		}
		float bodySize = chars.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		// normal line spacing: the median gap between body lines on the same page
		double[] gaps = body.stream().filter(l -> l.size() == bodySize && l.gap() > 0 && l.gap() < 100).mapToDouble(Line::gap).sorted().toArray();
		float lineH = gaps.length == 0 ? bodySize * 1.25f : (float) gaps[gaps.length / 5];
		// heading sizes, biggest first: the biggest that's used more than once is the section level
		List<Float> hSizes = chars.keySet().stream().filter(s -> s >= bodySize * 1.12).sorted((a, b) -> Float.compare(b, a)).toList();
		String title = null;
		Line first = body.get(0);
		boolean firstIsTitle = first.page() == 1 && !hSizes.isEmpty() && first.size() == hSizes.get(0)
				&& body.stream().filter(l -> l.size() == first.size()).count() == 1;
		if (firstIsTitle) {
			title = first.text();
		}
		List<Float> levels = firstIsTitle ? hSizes.stream().filter(s -> s < first.size()).toList() : hSizes;
		List<Section> out = new ArrayList<>();
		String heading = null;
		StringBuilder html = new StringBuilder();
		StringBuilder para = new StringBuilder();
		String list = null;
		for (int i = firstIsTitle ? 1 : 0; i < body.size(); i++) {
			Line l = body.get(i);
			int lvl = levels.indexOf(l.size());
			boolean shortBold = l.bold() && l.text().length() <= 90 && !l.text().endsWith(".") && !l.text().endsWith(",") && l.size() >= bodySize;
			String before = i > 0 ? body.get(i - 1).text() : "";
			boolean afterSentence = before.isEmpty() || before.matches(".*[.:;!?)]$") || body.get(i - 1).page() != l.page();
			boolean isHeading = lvl >= 0 || (shortBold && (l.gap() > lineH * 1.25 || i == 0 || afterSentence));
			if (isHeading && l.text().length() <= 140) {
				flushPara(html, para, list);
				list = closeList(html, list);
				boolean section = levels.isEmpty() ? shortBold : lvl == 0;
				if (section || heading == null && out.isEmpty()) {
					if (heading != null || html.length() > 0) {
						out.add(new Section(heading == null ? "Introduction" : heading, html.toString()));
					}
					heading = l.text();
					html.setLength(0);
				}
				else {
					String tag = lvl == 1 || lvl < 0 ? "h3" : "h4";
					html.append('<').append(tag).append('>').append(esc(l.text())).append("</").append(tag).append('>');
				}
				continue;
			}
			var m = BULLET.matcher(l.text());
			boolean newBlock = l.gap() > lineH * 1.3 || l.gap() == 999;
			if (m.matches() && m.group(2).length() > 0) {
				flushPara(html, para, list);
				String kind = m.group(1).matches("\\(?([0-9]{1,2}|[a-zA-Z])[.)]") ? "ol" : "ul";
				if (!kind.equals(list)) {
					list = closeList(html, list);
					html.append('<').append(kind).append('>');
					list = kind;
				}
				para.append(m.group(2).strip());
				continue;
			}
			if (list != null && !newBlock && para.length() > 0) {
				para.append(' ').append(l.text());
				continue;
			}
			if (newBlock) {
				flushPara(html, para, list);
				list = closeList(html, list);
			}
			if (para.length() > 0) {
				String prev = para.toString();
				para.append(prev.endsWith("-") ? "" : " ");
				if (prev.endsWith("-")) {
					para.setLength(para.length() - 1);
				}
			}
			para.append(l.text());
		}
		flushPara(html, para, list);
		closeList(html, list);
		if (heading != null || html.length() > 0) {
			out.add(new Section(heading == null ? (title == null ? "Policy" : "Introduction") : heading, html.toString()));
		}
		return new Result(title, merge(out), pages);
	}

	private static void flushPara(StringBuilder html, StringBuilder para, String list) {
		if (para.length() == 0) {
			return;
		}
		String t = esc(para.toString().replaceAll("\\s+", " ").strip());
		html.append(list != null ? "<li>" + t + "</li>" : "<p>" + t + "</p>");
		para.setLength(0);
	}

	private static String closeList(StringBuilder html, String list) {
		if (list != null) {
			html.append("</").append(list).append('>');
		}
		return null;
	}

	/** Sections with the same heading (a heading repeated on a new page) are joined. */
	private static List<Section> merge(List<Section> in) {
		Map<String, StringBuilder> by = new LinkedHashMap<>();
		for (Section s : in) {
			by.computeIfAbsent(s.heading(), k -> new StringBuilder()).append(s.body());
		}
		List<Section> out = new ArrayList<>();
		by.forEach((h, b) -> {
			if (!h.isBlank() || b.length() > 0) {
				out.add(new Section(h.isBlank() ? "Untitled section" : h, b.toString()));
			}
		});
		return out;
	}

	private static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ").strip();
	}

	// ================================================================== Word (.docx)

	private static Result docx(byte[] bytes) {
		String xml = null;
		String numbering = null;
		try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry e;
			long total = 0;
			while ((e = z.getNextEntry()) != null) {
				if (e.getName().equals("word/document.xml") || e.getName().equals("word/numbering.xml")) {
					String s = new String(readCapped(z, 30L * 1024 * 1024), StandardCharsets.UTF_8);
					total += s.length();
					if (e.getName().equals("word/document.xml")) {
						xml = s;
					}
					else {
						numbering = s;
					}
				}
				if (total > 40L * 1024 * 1024) {
					throw bad("This file is too large to read");
				}
			}
		}
		catch (IOException ex) {
			throw bad("This Word file couldn't be read");
		}
		if (xml == null) {
			throw bad("This doesn't look like a Word (.docx) file");
		}
		Map<String, String> numFmt = numberFormats(numbering);
		Document d = Jsoup.parse(xml, "", Parser.xmlParser());
		Element bodyEl = d.selectFirst("w|body");
		if (bodyEl == null) {
			return new Result(null, List.of(), 1);
		}
		// one entry per paragraph or table, with its size (half-points) and whether it's all bold
		record Para(Element el, String html, String plain, float size, boolean bold, int styleLvl, String style) {
		}
		List<Para> paras = new ArrayList<>();
		for (Element el : bodyEl.children()) {
			if (el.tagName().equals("w:tbl")) {
				paras.add(new Para(el, table(el), null, 0, false, 0, ""));
			}
			else if (el.tagName().equals("w:p")) {
				String raw = runs(el);
				// "<tab>1<tab>text" is how some writers number a list by hand
				var tabNum = Pattern.compile("^\\s*\\t*([0-9]{1,2}|[a-zA-Z])[.)]?\\t+").matcher(Jsoup.parse(raw.replace("\t", "\u0009")).wholeText());
				String html = raw.replace('\t', ' ').strip();
				String plain = Jsoup.parse(html).text().strip();
				if (tabNum.find() && !plain.isEmpty()) {
					String num = tabNum.group(1);
					plain = num + ". " + plain.substring(Math.min(plain.length(), plain.indexOf(num) + num.length())).replaceFirst("^[.)]?\\s*", "");
				}
				if (plain.isEmpty()) {
					continue;
				}
				float size = 0;
				boolean allBold = true;
				for (Element r : el.select("w|r")) {
					if (r.select("w|t").text().isBlank()) {
						continue;
					}
					Element pr = r.selectFirst("w|rPr");
					Element sz = pr == null ? null : pr.selectFirst("w|sz");
					try {
						size = Math.max(size, sz == null ? 0 : Float.parseFloat(sz.attr("w:val")) / 2);
					}
					catch (NumberFormatException e) {
						// keep the size found so far
					}
					allBold &= pr != null && on(pr, "w|b");
				}
				paras.add(new Para(el, html, plain, size, allBold, headingLevel(el), style(el)));
			}
		}
		boolean styled = paras.stream().anyMatch(x -> x.styleLvl() > 0);
		int top = paras.stream().filter(x -> x.styleLvl() > 0).mapToInt(Para::styleLvl).min().orElse(1);
		// without heading styles: larger text is a heading, the largest level is the section, and a lone biggest first line is the title
		Map<Float, Integer> chars = new HashMap<>();
		paras.stream().filter(x -> x.plain() != null && x.size() > 0).forEach(x -> chars.merge(x.size(), x.plain().length(), Integer::sum));
		float bodySize = chars.isEmpty() ? 0 : chars.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
		List<Float> levels = chars.keySet().stream().filter(z -> bodySize > 0 && z >= bodySize * 1.12).sorted((x, y) -> Float.compare(y, x)).toList();
		String title = null;
		int start = 0;
		if (!paras.isEmpty() && paras.get(0).plain() != null) {
			Para f = paras.get(0);
			if ("title".equals(f.style()) || (!styled && !levels.isEmpty() && f.size() == levels.get(0) && paras.stream().filter(x -> x.size() == f.size()).count() == 1)) {
				title = f.plain();
				start = 1;
				if (!styled) {
					levels = levels.stream().filter(z -> z < f.size()).toList();
				}
			}
		}
		List<Section> out = new ArrayList<>();
		String heading = null;
		StringBuilder html = new StringBuilder();
		String list = null;
		for (int i = start; i < paras.size(); i++) {
			Para x = paras.get(i);
			if (x.plain() == null) {
				list = closeList(html, list);
				html.append(x.html());
				continue;
			}
			if ("title".equals(x.style())) {
				title = title == null ? x.plain() : title;
				continue;
			}
			int lvl;
			if (styled) {
				lvl = x.styleLvl() > 0 ? x.styleLvl() - top : -1;
			}
			else {
				int li = levels.indexOf(x.size());
				boolean boldHead = x.bold() && x.plain().length() <= 90 && !x.plain().endsWith(".") && !x.plain().endsWith(",");
				lvl = li >= 0 ? li : boldHead ? (levels.isEmpty() ? 0 : levels.size()) : -1;
			}
			if (lvl >= 0 && x.plain().length() <= 140) {
				list = closeList(html, list);
				if (lvl == 0) {
					if (heading != null || html.length() > 0) {
						out.add(new Section(heading == null ? "Introduction" : heading, html.toString()));
					}
					heading = x.plain();
					html.setLength(0);
				}
				else {
					String tag = lvl == 1 ? "h3" : "h4";
					html.append('<').append(tag).append('>').append(esc(x.plain())).append("</").append(tag).append('>');
				}
				continue;
			}
			Element numPr = x.el().selectFirst("w|pPr > w|numPr");
			var bm = BULLET.matcher(x.plain());
			if (numPr != null || (bm.matches() && !bm.group(2).isBlank())) {
				String kind;
				String item;
				if (numPr != null) {
					Element numId = numPr.selectFirst("w|numId");
					String fmt = numId == null ? "bullet" : numFmt.getOrDefault(numId.attr("w:val"), "bullet");
					kind = "bullet".equals(fmt) || "none".equals(fmt) ? "ul" : "ol";
					item = x.html();
				}
				else {
					kind = bm.group(1).matches("\\(?([0-9]{1,2}|[a-zA-Z])[.)]") ? "ol" : "ul";
					item = esc(bm.group(2).strip());
				}
				if (!kind.equals(list)) {
					list = closeList(html, list);
					html.append('<').append(kind).append('>');
					list = kind;
				}
				html.append("<li>").append(item).append("</li>");
				continue;
			}
			list = closeList(html, list);
			Element jc = x.el().selectFirst("w|pPr > w|jc");
			String align = jc == null ? "" : jc.attr("w:val");
			String st = "center".equals(align) ? " style=\"text-align:center\"" : "right".equals(align) || "end".equals(align) ? " style=\"text-align:right\"" : "both".equals(align) ? " style=\"text-align:justify\"" : "";
			if ("quote".equals(x.style()) || "intensequote".equals(x.style())) {
				html.append("<blockquote>").append(x.html()).append("</blockquote>");
			}
			else {
				html.append("<p").append(st).append('>').append(x.html()).append("</p>");
			}
		}
		closeList(html, list);
		if (heading != null || html.length() > 0) {
			out.add(new Section(heading == null ? (title == null ? "Policy" : "Introduction") : heading, html.toString()));
		}
		return new Result(title, merge(out), 1);
	}

	private static String style(Element p) {
		Element s = p.selectFirst("w|pPr > w|pStyle");
		return s == null ? "" : s.attr("w:val").toLowerCase(Locale.ROOT).replace(" ", "");
	}

	/** Heading 1–6 from the paragraph style or its outline level; 0 when it's not a heading. */
	private static int headingLevel(Element p) {
		String s = style(p);
		var m = Pattern.compile("^heading([1-6])$").matcher(s);
		if (m.matches()) {
			return Integer.parseInt(m.group(1));
		}
		Element ol = p.selectFirst("w|pPr > w|outlineLvl");
		if (ol != null) {
			try {
				int v = Integer.parseInt(ol.attr("w:val"));
				if (v >= 0 && v < 6) {
					return v + 1;
				}
			}
			catch (NumberFormatException e) {
				return 0;
			}
		}
		return 0;
	}

	/** The paragraph's text with bold, italic, underline and strike kept. */
	private static String runs(Element p) {
		StringBuilder b = new StringBuilder();
		for (Element r : p.select("w|r")) {
			StringBuilder t = new StringBuilder();
			for (Element c : r.children()) {
				switch (c.tagName()) {
					case "w:t" -> t.append(esc(c.wholeText()));
					case "w:tab" -> t.append('\t');
					case "w:br", "w:cr" -> t.append("<br>");
					default -> {
					}
				}
			}
			if (t.isEmpty()) {
				continue;
			}
			Element pr = r.selectFirst("w|rPr");
			String s = t.toString();
			if (pr != null) {
				if (on(pr, "w|b")) {
					s = "<b>" + s + "</b>";
				}
				if (on(pr, "w|i")) {
					s = "<i>" + s + "</i>";
				}
				if (pr.selectFirst("w|u") != null && !"none".equals(pr.selectFirst("w|u").attr("w:val"))) {
					s = "<u>" + s + "</u>";
				}
				if (on(pr, "w|strike")) {
					s = "<s>" + s + "</s>";
				}
			}
			b.append(s);
		}
		return b.toString().replace("</b><b>", "").replace("</i><i>", "");
	}

	private static boolean on(Element pr, String q) {
		Element e = pr.selectFirst(q);
		return e != null && !"0".equals(e.attr("w:val")) && !"false".equals(e.attr("w:val"));
	}

	private static String table(Element tbl) {
		StringBuilder b = new StringBuilder("<table><tbody>");
		boolean first = true;
		for (Element tr : tbl.select("> w|tr")) {
			b.append("<tr>");
			for (Element tc : tr.select("> w|tc")) {
				StringBuilder cell = new StringBuilder();
				for (Element p : tc.select("> w|p")) {
					String t = runs(p);
					if (!t.isBlank()) {
						cell.append(cell.isEmpty() ? "" : "<br>").append(t);
					}
				}
				Element span = tc.selectFirst("w|tcPr > w|gridSpan");
				String cs = span == null ? "" : " colspan=\"" + span.attr("w:val").replaceAll("\\D", "") + "\"";
				String tag = first ? "th" : "td";
				b.append('<').append(tag).append(cs).append('>').append(cell.isEmpty() ? "&nbsp;" : cell).append("</").append(tag).append('>');
			}
			b.append("</tr>");
			first = false;
		}
		return b.append("</tbody></table>").toString();
	}

	/** numId → "bullet" / "decimal" / … from numbering.xml (first level). */
	private static Map<String, String> numberFormats(String xml) {
		Map<String, String> out = new HashMap<>();
		if (xml == null) {
			return out;
		}
		Document d = Jsoup.parse(xml, "", Parser.xmlParser());
		Map<String, String> abs = new HashMap<>();
		for (Element a : d.select("w|abstractNum")) {
			Element lvl = a.selectFirst("w|lvl[w:ilvl=0] > w|numFmt");
			abs.put(a.attr("w:abstractNumId"), lvl == null ? "bullet" : lvl.attr("w:val"));
		}
		for (Element n : d.select("w|num")) {
			Element a = n.selectFirst("w|abstractNumId");
			if (a != null) {
				out.put(n.attr("w:numId"), abs.getOrDefault(a.attr("w:val"), "bullet"));
			}
		}
		return out;
	}

	private static byte[] readCapped(InputStream in, long cap) throws IOException {
		ByteArrayOutputStream o = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		long t = 0;
		while ((n = in.read(buf)) > 0) {
			t += n;
			if (t > cap) {
				throw bad("This file is too large to read");
			}
			o.write(buf, 0, n);
		}
		return o.toByteArray();
	}

	// ================================================================== helpers

	private static String esc(String s) {
		return Entities.escape(s == null ? "" : s);
	}

	private static String cut(String s, int n) {
		String t = s == null ? "" : s.strip();
		return t.length() > n ? t.substring(0, n).strip() : t;
	}

	private static String baseName(String f) {
		if (f == null || f.isBlank()) {
			return "Imported policy";
		}
		String n = f.replaceAll(".*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[_-]+", " ").strip();
		return n.isEmpty() ? "Imported policy" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}

	private static UnprocessableException bad(String msg) {
		return new UnprocessableException(List.of(ErrorItem.field("file", "INVALID", msg)));
	}
}
