package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.onboarding.OnboardingConfig;
import com.ivoyant.hrms.common.storage.FileStore;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Local profile only: a handful of new hires in every pipeline status so the screens have something to show. Skipped
 * when any hire exists. Logs the portal link token of the invited ones.
 */
@Component
@Profile("local")
@Order(5)
class OnboardingSeed implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(OnboardingSeed.class);
	private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final byte[] PDF = "%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

	private enum Mode { DRAFT, INVITED, PROGRESS, REVIEW, ACTION, READY }

	private record Seed(String first, String last, String title, String dept, String location, String type, String manager, int dojOffset, Mode mode) {
	}

	private static final List<Seed> HIRES = List.of(
			new Seed("Vivek", "Rathore", "Software Engineer", "Engineering", "Bengaluru HQ", "FT", "EMP008", 14, Mode.REVIEW),
			new Seed("Sana", "Qureshi", "Product Designer", "Design", "Bengaluru HQ", "FT", "EMP007", 10, Mode.ACTION),
			new Seed("Rahul", "Jain", "Sales Executive", "Sales", "Mumbai BKC", "FT", "EMP005", 21, Mode.INVITED),
			new Seed("Meghna", "Das", "Accountant", "Finance", "Mumbai BKC", "FT", "EMP004", 28, Mode.DRAFT),
			new Seed("Kunal", "Shah", "Software Engineer", "Engineering", "Hyderabad office", "FT", "EMP003", 3, Mode.READY),
			new Seed("Pooja", "Verma", "Recruiter", "People", "Bengaluru HQ", "FT", "EMP002", 17, Mode.PROGRESS),
			new Seed("Ishaan", "Roy", "Design Intern", "Design", "Bengaluru HQ", "IN", "EMP007", 30, Mode.DRAFT),
			new Seed("Tara", "Menon", "Contract Engineer", "Engineering", "Hyderabad office", "CT", "EMP003", 12, Mode.INVITED));

	private final JdbcClient jdbc;
	private final OnboardingConfig config;
	private final HireStatusService status;
	private final FileStore store;
	private final Sensitive sensitive;

	OnboardingSeed(JdbcClient jdbc, OnboardingConfig config, HireStatusService status, FileStore store, Sensitive sensitive) {
		this.jdbc = jdbc;
		this.config = config;
		this.status = status;
		this.store = store;
		this.sensitive = sensitive;
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		if (jdbc.sql("select count(*) from onboarding_hire").query(Long.class).single() > 0) {
			return;
		}
		int made = 0;
		for (Seed s : HIRES) {
			UUID dept = one("select id from department where org_id = :o and name = :n", s.dept());
			UUID loc = one("select id from location where org_id = :o and name = :n", s.location());
			UUID type = one("select id from employment_type where org_id = :o and code = :n", s.type());
			UUID mgr = one("select id from employee where org_id = :o and employee_code = :n", s.manager());
			if (dept == null || loc == null || type == null || mgr == null) {
				log.warn("onboarding seed: skipped {} {} (department, location, type or manager not found)", s.first(), s.last());
				continue;
			}
			UUID id = UUID.randomUUID();
			String email = (s.first() + "." + s.last()).toLowerCase() + "@example.com";
			jdbc.sql("""
					insert into onboarding_hire (id, org_id, first_name, last_name, personal_email, phone, title, department_id, manager_id, location_id,
					  employment_type_id, doj, probation_months, contract_end_date)
					values (:id, :o, :f, :l, :e, '+91 98450 22117', :t, :d, :m, :loc, :et, :doj, 6, :cend)""")
					.param("id", id).param("o", ORG).param("f", s.first()).param("l", s.last()).param("e", email).param("t", s.title()).param("d", dept)
					.param("m", mgr).param("loc", loc).param("et", type).param("doj", LocalDate.now().plusDays(s.dojOffset()))
					.param("cend", "CT".equals(s.type()) || "IN".equals(s.type()) ? LocalDate.now().plusDays(s.dojOffset() + 365) : null).update();
			int sort = 10;
			for (OnboardingConfig.DocumentView d : config.documentsFor(type)) {
				jdbc.sql("insert into onboarding_hire_document (hire_id, document_id, doc_key, required, sort) values (:h, :d, :k, :r, :s)")
						.param("h", id).param("d", d.id()).param("k", d.key()).param("r", d.required()).param("s", sort).update();
				sort += 10;
			}
			sort = 10;
			for (OnboardingConfig.TaskTemplate t : config.checklist()) {
				jdbc.sql("insert into onboarding_task (hire_id, template_id, name, phase, owner, sort) values (:h, :t, :n, :p, :o, :s)")
						.param("h", id).param("t", t.id()).param("n", t.name()).param("p", t.phase()).param("o", t.owner()).param("s", sort).update();
				sort += 10;
			}
			jdbc.sql("insert into onboarding_event (hire_id, kind, title, description) values (:h, 'GEN', 'Hire created', 'Seed data')").param("h", id).update();
			if (s.mode() != Mode.DRAFT) {
				invite(id, email, s.dojOffset(), s.mode() == Mode.INVITED || s.mode() == Mode.REVIEW ? s.first() : null);
			}
			if (s.mode() == Mode.PROGRESS || s.mode() == Mode.REVIEW || s.mode() == Mode.ACTION || s.mode() == Mode.READY) {
				details(id);
			}
			documents(id, s.mode());
			if (s.mode() == Mode.READY) {
				jdbc.sql("""
						insert into onboarding_m365 (hire_id, upn, license_sku, groups, state, step, simulated, created_at)
						values (:h, :u, 'Microsoft 365 Business Standard', '["All staff"]', 'ACTIVE', 4, true, now())""")
						.param("h", id).param("u", (s.first() + "." + s.last()).toLowerCase() + "@ivoyant.com").update();
			}
			status.recompute(id);
			made++;
		}
		log.info("Onboarding seed ready ({} hires)", made);
	}

	private UUID one(String sql, String name) {
		return jdbc.sql(sql).param("o", ORG).param("n", name).query(UUID.class).optional().orElse(null);
	}

	private void invite(UUID id, String email, int dojOffset, String logName) {
		byte[] raw = new byte[32];
		new SecureRandom().nextBytes(raw);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		jdbc.sql("""
				insert into onboarding_invite (hire_id, sent_to, token_hash, expires_at, first_sent_at, last_sent_at, due_date, remind_every_days)
				values (:h, :e, :t, now() + make_interval(days => :d), now() - interval '2 days', now() - interval '2 days', :due, 2)""")
				.param("h", id).param("e", email).param("t", InviteService.hash(token)).param("d", dojOffset + 8).param("due", LocalDate.now().plusDays(Math.max(0, dojOffset - 3))).update();
		jdbc.sql("insert into onboarding_event (hire_id, kind, title, description) values (:h, 'MAIL', 'Invite sent', :d)").param("h", id).param("d", "To " + email).update();
		if (logName != null) {
			log.info("Onboarding portal link for {}: header  X-Onboarding-Token: {}", logName, token);
		}
	}

	private void details(UUID id) {
		String[][] rows = {{"legal", "Seed Hire"}, {"ifsc", "HDFC0001234"}, {"addr", "12 MG Road, Bengaluru"}, {"marital", "Single"}};
		for (String[] r : rows) {
			jdbc.sql("insert into onboarding_hire_detail (hire_id, field_key, value) values (:h, :k, :v) on conflict do nothing").param("h", id).param("k", r[0]).param("v", r[1]).update();
		}
		jdbc.sql("insert into onboarding_hire_detail (hire_id, field_key, value_enc) values (:h, 'pan', :v) on conflict do nothing").param("h", id).param("v", sensitive.encrypt("ABCDE1234F")).update();
		jdbc.sql("insert into onboarding_hire_detail (hire_id, field_key, value_enc) values (:h, 'acct', :v) on conflict do nothing").param("h", id).param("v", sensitive.encrypt("123456782301")).update();
		jdbc.sql("insert into onboarding_hire_detail (hire_id, field_key, value_enc) values (:h, 'aadhaar', :v) on conflict do nothing").param("h", id).param("v", sensitive.encrypt("123412341234")).update();
		jdbc.sql("update onboarding_hire set details_submitted_at = now() - interval '1 day' where id = :id").param("id", id).update();
		jdbc.sql("insert into onboarding_event (hire_id, kind, title, description, actor_is_hire) values (:h, 'OK', 'Details submitted', 'Seed data', true)").param("h", id).update();
	}

	private void documents(UUID hireId, Mode mode) throws Exception {
		List<UUID> docs = jdbc.sql("select id from onboarding_hire_document where hire_id = :h order by sort").param("h", hireId).query(UUID.class).list();
		for (int i = 0; i < docs.size(); i++) {
			String st = switch (mode) {
				case READY -> "VERIFIED";
				case REVIEW -> i < 2 ? "VERIFIED" : i < 4 ? "SUBMITTED" : "MISSING";
				case ACTION -> i == 0 ? "REJECTED" : i == 1 ? "SUBMITTED" : "MISSING";
				default -> "MISSING";
			};
			if (st.equals("MISSING")) {
				continue;
			}
			String key = ORG + "/" + hireId + "/" + docs.get(i) + "/seed";
			store.put(key, PDF);
			jdbc.sql("insert into onboarding_hire_file (hire_document_id, storage_key, file_name, mime, size_bytes, sha256, scan_state) values (:d, :k, 'scan.pdf', 'application/pdf', :s, 'seed', 'CLEAN')")
					.param("d", docs.get(i)).param("k", key).param("s", PDF.length).update();
			jdbc.sql("update onboarding_hire_document set status = :s, note = :n, verified_at = case when :s = 'VERIFIED' then now() else null end where id = :id")
					.param("s", st).param("n", st.equals("REJECTED") ? "Image is blurred — please upload a clearer scan" : null).param("id", docs.get(i)).update();
		}
	}
}
