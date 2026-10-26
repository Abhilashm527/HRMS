package com.ivoyant.hrms.employee.service;

import java.util.ArrayList;
import java.util.List;

/** Just enough CSV (RFC 4180): quoted fields, doubled quotes, newlines inside quotes. */
final class Csv {

	private Csv() {
	}

	static List<List<String>> parse(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		String t = text.startsWith("﻿") ? text.substring(1) : text;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					}
					else {
						quoted = false;
					}
				}
				else {
					cell.append(c);
				}
			}
			else if (c == '"') {
				quoted = true;
			}
			else if (c == ',') {
				row.add(cell.toString());
				cell.setLength(0);
			}
			else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') {
					i++;
				}
				row.add(cell.toString());
				cell.setLength(0);
				if (!(row.size() == 1 && row.get(0).isEmpty())) {
					rows.add(row);
				}
				row = new ArrayList<>();
			}
			else {
				cell.append(c);
			}
		}
		if (cell.length() > 0 || !row.isEmpty()) {
			row.add(cell.toString());
			rows.add(row);
		}
		return rows;
	}

	/** One cell, quoted; a leading = + - @ is defused so a spreadsheet does not run it as a formula. */
	static String cell(Object o) {
		String s = o == null ? "" : o.toString();
		if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) {
			s = "'" + s;
		}
		return "\"" + s.replace("\"", "\"\"") + "\"";
	}
}
