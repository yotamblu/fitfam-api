package com.fitfam.api.fitbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Once a day, deletes Fitbot conversations that nobody has touched for the retention period (100 days by default). */
@Configuration
@EnableScheduling
public class FitbotRetentionJob {

	private static final Logger log = LoggerFactory.getLogger(FitbotRetentionJob.class);

	private final FitbotService fitbot;

	public FitbotRetentionJob(FitbotService fitbot) {
		this.fitbot = fitbot;
	}

	@Scheduled(cron = "0 30 3 * * *", zone = "Asia/Jerusalem")
	public void deleteExpired() {
		try {
			int removed = fitbot.deleteExpired();
			if (removed > 0) {
				log.info("Fitbot retention: deleted {} old conversations", removed);
			}
		}
		catch (RuntimeException e) {
			log.warn("Fitbot retention job failed: {}", e.getClass().getSimpleName());
		}
	}

}
