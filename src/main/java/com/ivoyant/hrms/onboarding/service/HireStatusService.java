package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.onboarding.domain.HireStatus;
import com.ivoyant.hrms.org.OrgProfile;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recomputes the cached status and stages of a hire from its facts; called by every write that can change them. */
@Service
public class HireStatusService {

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final OrgProfile org;
	private final Notifier notifier;
	private final HrOwners owners;

	HireStatusService(JdbcClient jdbc, HireRepo repo, OrgProfile org, Notifier notifier, HrOwners owners) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.org = org;
		this.notifier = notifier;
		this.owners = owners;
	}

	public LocalDate today() {
		return LocalDate.now(org.zone());
	}

	@Transactional
	public HireStatus.Result recompute(UUID hireId) {
		Hire h = repo.find(hireId).orElseThrow();
		List<HireStatus.Doc> docs = jdbc.sql("select doc_key, required, status from onboarding_hire_document where hire_id = :h")
				.param("h", hireId).query((rs, i) -> new HireStatus.Doc(rs.getString(1), rs.getBoolean(2), rs.getString(3))).list();
		boolean m365 = jdbc.sql("select count(*) from onboarding_m365 where hire_id = :h and state = 'ACTIVE'").param("h", hireId)
				.query(Long.class).single() > 0;
		boolean invited = jdbc.sql("select count(*) from onboarding_invite where hire_id = :h and revoked_at is null").param("h", hireId)
				.query(Long.class).single() > 0 || (h.joinedAt() != null);
		HireStatus.Result r = HireStatus.compute(new HireStatus.Facts(m365, invited, h.detailsSubmittedAt() != null, docs, h.doj(),
				today(), h.joinedAt() != null));
		jdbc.sql("update onboarding_hire set status = :s, stages = :st::boolean[], updated_at = now() where id = :id")
				.param("s", r.status()).param("st", toArrayLiteral(r.stages())).param("id", hireId).update();
		if ("READY_TO_JOIN".equals(r.status()) && !"READY_TO_JOIN".equals(h.status())) {
			Set<UUID> to = owners.forHire(h);
			notifier.inApp(to, h.name() + " is ready to join on " + h.doj(), "All documents are verified.", "/onboarding/" + hireId);
		}
		return r;
	}

	static String toArrayLiteral(boolean[] a) {
		StringBuilder sb = new StringBuilder("{");
		for (int i = 0; i < a.length; i++) {
			sb.append(i == 0 ? "" : ",").append(a[i] ? "t" : "f");
		}
		return sb.append('}').toString();
	}
}
