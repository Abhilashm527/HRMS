package com.ivoyant.hrms.policy.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

/**
 * Policy text is formatted HTML written in the document editor. Everything saved goes through {@link #clean}: only document tags
 * survive, styles are limited to typography and table properties, links must be http(s)/mailto, and images must be inline data or
 * https. Older policies hold plain text; {@link #toHtml} turns that into paragraphs.
 */
final class PolicyHtml {

	private PolicyHtml() {
	}

	private static final Safelist SAFE = new Safelist()
			.addTags("p", "h2", "h3", "h4", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th", "br", "img", "blockquote", "a", "b", "strong", "i", "em",
					"u", "s", "sub", "sup", "code", "pre", "span", "hr")
			.addAttributes(":all", "style").addAttributes("a", "href", "target", "rel").addAttributes("img", "src", "alt").addAttributes("td", "colspan", "rowspan")
			.addAttributes("th", "colspan", "rowspan").addAttributes("ol", "start").addProtocols("a", "href", "http", "https", "mailto", "#")
			.addProtocols("img", "src", "https", "data").preserveRelativeLinks(false);

	private static final Set<String> CSS = Set.of("color", "background-color", "background", "font-weight", "font-style", "font-size", "font-family", "text-decoration",
			"text-decoration-line", "text-align", "margin", "margin-top", "margin-bottom", "margin-left", "margin-right", "padding", "padding-left", "padding-right",
			"padding-top", "padding-bottom", "border", "border-left", "border-collapse", "border-radius", "width", "max-width", "min-width", "height", "display",
			"vertical-align", "line-height", "list-style-type");

	/** Cleans editor HTML; plain text (no tags) is turned into paragraphs first. */
	static String clean(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String html = looksHtml(raw) ? raw : toHtml(raw);
		Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);
		String safe = Jsoup.clean(html, "", SAFE, out);
		Document d = Jsoup.parseBodyFragment(safe);
		d.outputSettings(out);
		for (Element e : d.body().getAllElements()) {
			if (e.hasAttr("style")) {
				String st = style(e.attr("style"));
				if (st.isEmpty()) {
					e.removeAttr("style");
				}
				else {
					e.attr("style", st);
				}
			}
			if (e.normalName().equals("img")) {
				String src = e.attr("src");
				if (!(src.startsWith("https://") || src.matches("^data:image/(png|jpe?g|gif|webp);base64,[A-Za-z0-9+/=\\s]+$"))) {
					e.remove();
				}
			}
			if (e.normalName().equals("a")) {
				e.attr("target", "_blank").attr("rel", "noopener noreferrer");
			}
		}
		return d.body().html();
	}

	/** Keeps only allowed CSS properties with harmless values. */
	private static String style(String s) {
		List<String> keep = new ArrayList<>();
		for (String decl : s.split(";")) {
			int c = decl.indexOf(':');
			if (c < 0) {
				continue;
			}
			String k = decl.substring(0, c).trim().toLowerCase(Locale.ROOT);
			String v = decl.substring(c + 1).trim();
			String lv = v.toLowerCase(Locale.ROOT);
			if (!CSS.contains(k) || v.isEmpty() || v.length() > 200 || lv.contains("url(") || lv.contains("expression") || lv.contains("javascript") || lv.contains("@import")
					|| v.contains("<") || v.contains("\\")) {
				continue;
			}
			keep.add(k + ":" + v);
		}
		return String.join(";", keep);
	}

	static boolean looksHtml(String s) {
		return s != null && s.stripLeading().startsWith("<");
	}

	/** Plain text to paragraphs: a blank line starts a new paragraph, a single line break stays a break. */
	static String toHtml(String text) {
		StringBuilder b = new StringBuilder();
		for (String para : text.strip().split("\\n\\s*\\n")) {
			if (para.isBlank()) {
				continue;
			}
			b.append("<p>").append(org.jsoup.nodes.Entities.escape(para.strip()).replace("\n", "<br>")).append("</p>");
		}
		return b.toString();
	}

	/** Readable text of a body, one block per line (used for sentence counts). */
	static String text(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}
		if (!looksHtml(body)) {
			return body;
		}
		StringBuilder b = new StringBuilder();
		for (Block x : blocks(body)) {
			b.append(x.text()).append('\n');
		}
		return b.toString();
	}

	/** One line of the document for the PDF: {@code kind} is p, h (sub-heading), li, row (table row, cells joined with ' | ') or img. */
	record Block(String kind, String text) {
	}

	static List<Block> blocks(String body) {
		List<Block> out = new ArrayList<>();
		if (body == null || body.isBlank()) {
			return out;
		}
		if (!looksHtml(body)) {
			for (String para : body.split("\\n+")) {
				if (!para.isBlank()) {
					out.add(new Block("p", para.strip()));
				}
			}
			return out;
		}
		walk(Jsoup.parseBodyFragment(body).body(), out);
		return out;
	}

	private static void walk(Element parent, List<Block> out) {
		for (Node n : parent.childNodes()) {
			if (n instanceof TextNode t) {
				if (!t.isBlank()) {
					out.add(new Block("p", t.text().strip()));
				}
				continue;
			}
			if (!(n instanceof Element e)) {
				continue;
			}
			switch (e.normalName()) {
				case "h2", "h3", "h4" -> add(out, "h", e.text());
				case "ul", "ol" -> {
					int i = e.normalName().equals("ol") ? parseInt(e.attr("start"), 1) : 0;
					for (Element li : e.children()) {
						add(out, "li", (i > 0 ? (i++) + ". " : "- ") + li.text());
					}
				}
				case "table" -> {
					for (Element tr : e.select("tr")) {
						add(out, "row", String.join("  |  ", tr.select("> td, > th").eachText()));
					}
				}
				case "img" -> out.add(new Block("img", "[Image]"));
				case "hr" -> out.add(new Block("p", "—"));
				case "p", "blockquote", "pre" -> {
					if (!e.select("img").isEmpty() && e.text().isBlank()) {
						out.add(new Block("img", "[Image]"));
					}
					else {
						add(out, "p", e.text());
					}
				}
				default -> {
					if (e.isBlock()) {
						walk(e, out);
					}
					else {
						add(out, "p", e.text());
					}
				}
			}
		}
	}

	private static void add(List<Block> out, String kind, String text) {
		if (text != null && !text.isBlank()) {
			out.add(new Block(kind, text.strip()));
		}
	}

	private static int parseInt(String s, int dflt) {
		try {
			return Integer.parseInt(s.trim());
		}
		catch (RuntimeException e) {
			return dflt;
		}
	}

	/** Header and footer lines are short formatted text: keep inline formatting only. */
	static String cleanLine(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		Safelist inline = new Safelist().addTags("p", "b", "strong", "i", "em", "u", "s", "span", "br").addAttributes(":all", "style");
		String s = Jsoup.clean(looksHtml(raw) ? raw : "<p>" + org.jsoup.nodes.Entities.escape(raw.strip()) + "</p>", "", inline,
				new Document.OutputSettings().prettyPrint(false));
		Document d = Jsoup.parseBodyFragment(s);
		d.outputSettings(new Document.OutputSettings().prettyPrint(false));
		for (Element e : d.body().getAllElements()) {
			if (e.hasAttr("style")) {
				String st = style(e.attr("style"));
				if (st.isEmpty()) {
					e.removeAttr("style");
				}
				else {
					e.attr("style", st);
				}
			}
		}
		String h = d.body().html();
		return h.length() > 2000 ? h.substring(0, 2000) : h;
	}
}
