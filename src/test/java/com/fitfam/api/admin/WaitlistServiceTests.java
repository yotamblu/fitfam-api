package com.fitfam.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.fitfam.api.admin.AdminDtos.WaitlistPageDto;
import com.fitfam.api.web.ApiException;

@ExtendWith(MockitoExtension.class)
class WaitlistServiceTests {

	@Mock
	private JdbcTemplate jdbc;

	@Test
	void pageAndSizeAreClamped() {
		assertThat(WaitlistService.clampPage(-3)).isZero();
		assertThat(WaitlistService.clampPage(4)).isEqualTo(4);
		assertThat(WaitlistService.clampSize(0)).isEqualTo(1);
		assertThat(WaitlistService.clampSize(-5)).isEqualTo(1);
		assertThat(WaitlistService.clampSize(50)).isEqualTo(50);
		assertThat(WaitlistService.clampSize(100_000)).isEqualTo(WaitlistService.MAX_SIZE);
	}

	@Test
	void queriesWithLimitAndOffsetForTheRequestedPage() {
		when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(120L);
		when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<AdminDtos.WaitlistEntryDto>>any(), any(Object[].class)))
				.thenReturn(List.of());

		WaitlistPageDto result = new WaitlistService(jdbc).list(2, 50);

		assertThat(result.total()).isEqualTo(120);
		assertThat(result.page()).isEqualTo(2);
		assertThat(result.size()).isEqualTo(50);
		assertThat(result.items()).isEmpty();
		// page 2 of size 50 starts at row 100
		verify(jdbc).query(anyString(), ArgumentMatchers.<RowMapper<AdminDtos.WaitlistEntryDto>>any(), eq(50), eq(100L));
	}

	@Test
	void missingWaitlistTableIsReportedAsUnavailable() {
		when(jdbc.queryForObject(anyString(), eq(Long.class)))
				.thenThrow(new BadSqlGrammarException("count", "select", new java.sql.SQLException("no such table")));

		assertThatThrownBy(() -> new WaitlistService(jdbc).list(0, 50))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(e.getCode()).isEqualTo("waitlist_unavailable");
				});
	}

}
