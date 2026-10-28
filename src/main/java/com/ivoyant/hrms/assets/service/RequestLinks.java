package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** When someone is given an asset or a seat, the oldest open request of theirs for the same type or licence is marked fulfilled. */
@Component
class RequestLinks {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	RequestLinks(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	void linkHardware(UUID employeeId, UUID typeId, UUID assetId, UUID requestId) {
		UUID target = requestId != null ? requestId : jdbc.sql("""
				select id from asset_request where org_id = :org and employee_id = :e and kind = 'HARDWARE' and asset_type_id = :t and status = 'APPROVED'
				order by created_at limit 1""").param("org", currentUser.orgId()).param("e", employeeId).param("t", typeId).query(UUID.class).optional().orElse(null);
		if (target != null) {
			jdbc.sql("update asset_request set status = 'FULFILLED', fulfilled_asset_id = :a, fulfilled_at = now(), version = version + 1 where id = :id and status in ('APPROVED', 'PENDING')")
					.param("a", assetId).param("id", target).update();
			jdbc.sql("update asset_assignment set request_id = :r where asset_id = :a and returned_on is null and request_id is null").param("r", target).param("a", assetId).update();
		}
	}

	void linkSeat(UUID employeeId, UUID licenseId, UUID seatId, UUID requestId) {
		UUID target = requestId != null ? requestId : jdbc.sql("""
				select id from asset_request where org_id = :org and employee_id = :e and kind = 'SOFTWARE' and license_id = :l and status = 'APPROVED'
				order by created_at limit 1""").param("org", currentUser.orgId()).param("e", employeeId).param("l", licenseId).query(UUID.class).optional().orElse(null);
		if (target != null) {
			jdbc.sql("update asset_request set status = 'FULFILLED', fulfilled_seat_id = :s, fulfilled_at = now(), version = version + 1 where id = :id and status in ('APPROVED', 'PENDING')")
					.param("s", seatId).param("id", target).update();
			jdbc.sql("update license_seat set request_id = :r where id = :s and request_id is null").param("r", target).param("s", seatId).update();
		}
	}
}
