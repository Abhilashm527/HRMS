package com.ivoyant.hrms.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What a person fills in to apply: the dates, whether it is half a day, whether a document is attached, and the balance they have (null: not checked). */
public record ApplyForm(LocalDate from, LocalDate to, boolean half, boolean hasDocument, BigDecimal available) {
}
