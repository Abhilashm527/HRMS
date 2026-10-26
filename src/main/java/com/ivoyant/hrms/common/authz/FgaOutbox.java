package com.ivoyant.hrms.common.authz;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transactional outbox for OpenFGA tuple changes (table {@code fga_outbox}).
 * <p>
 * Services call {@link #write} / {@link #delete} inside the same transaction as the business change; a scheduled
 * worker later pushes the rows to OpenFGA in order. OpenFGA is never called inside a business transaction.
 */
@Component
public class FgaOutbox {

	private static final Logger log = LoggerFactory.getLogger(FgaOutbox.class);
	private static final int BATCH_SIZE = 100;
	private static final int MAX_ATTEMPTS = 10;
	private static final int MAX_BATCHES_PER_RUN = 20;

	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final AuthzService authz;

	public FgaOutbox(JdbcClient jdbc, JsonMapper json, AuthzService authz) {
		this.jdbc = jdbc;
		this.json = json;
		this.authz = authz;
	}

	/** Joins the caller's transaction; fails if there is none. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void write(FgaTuple tuple) {
		enqueue("WRITE", tuple);
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void delete(FgaTuple tuple) {
		enqueue("DELETE", tuple);
	}

	private void enqueue(String op, FgaTuple tuple) {
		jdbc.sql("insert into fga_outbox (op, tuple) values (:op, cast(:tuple as jsonb))")
				.param("op", op)
				.param("tuple", json.writeValueAsString(tuple))
				.update();
	}

	private record Row(UUID id, String op, String tuple) {
	}

	@Scheduled(fixedDelay = 3000, initialDelay = 10000)
	public void drain() {
		if (!authz.configured()) {
			return;
		}
		for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
			if (!drainBatch()) {
				return;
			}
		}
	}

	/** Pushes one batch of consecutive same-op rows; returns true if more work may remain. */
	@Transactional
	boolean drainBatch() {
		List<Row> rows = jdbc.sql("""
				select id, op, tuple::text as tuple from fga_outbox
				where processed_at is null and attempts < :max
				order by created_at, id
				limit :limit
				for update skip locked
				""")
				.param("max", MAX_ATTEMPTS)
				.param("limit", BATCH_SIZE)
				.query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("op"), rs.getString("tuple")))
				.list();
		if (rows.isEmpty()) {
			return false;
		}
		// keep tuple changes in order: only batch the leading run of rows that share one op
		String op = rows.get(0).op();
		List<Row> batch = rows.stream().takeWhile(r -> r.op().equals(op)).toList();
		try {
			List<FgaTuple> tuples = batch.stream().map(r -> json.readValue(r.tuple(), FgaTuple.class)).toList();
			if ("WRITE".equals(op)) {
				authz.write(tuples);
			}
			else {
				authz.delete(tuples);
			}
			jdbc.sql("update fga_outbox set processed_at = now() where id in (:ids)")
					.param("ids", batch.stream().map(Row::id).toList())
					.update();
			return rows.size() == BATCH_SIZE || batch.size() < rows.size();
		}
		catch (RuntimeException ex) {
			log.warn("Failed to push {} outbox row(s) to OpenFGA: {}", batch.size(), ex.getMessage());
			jdbc.sql("update fga_outbox set attempts = attempts + 1, last_error = :err where id in (:ids)")
					.param("err", String.valueOf(ex.getMessage()))
					.param("ids", batch.stream().map(Row::id).toList())
					.update();
			return false;
		}
	}
}
