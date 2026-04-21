package com.resumestudio.tracker;

import com.resumestudio.auth.UserRepository;
import com.resumestudio.auth.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class JobReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobReminderScheduler.class);
    private static final int MIN_FREQUENCY_DAYS = 1;
    private static final int MAX_FREQUENCY_DAYS = 14;

    private final JobApplicationRepository repo;
    private final UserRepository userRepo;
    private final TrackerReminderEmailSender emailSender;
    private final boolean remindersEnabled;
    private final AtomicBoolean missingProviderLogged = new AtomicBoolean(false);

    public JobReminderScheduler(
        JobApplicationRepository repo,
        UserRepository userRepo,
        TrackerReminderEmailSender emailSender,
        @Value("${tracker.reminders.enabled:true}") boolean remindersEnabled
    ) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.emailSender = emailSender;
        this.remindersEnabled = remindersEnabled;
    }

    @Scheduled(fixedDelayString = "${tracker.reminders.interval-ms:1800000}")
    @Transactional
    public void sendDueReminders() {
        if (!remindersEnabled) return;
        if (!emailSender.isConfigured()) {
            if (missingProviderLogged.compareAndSet(false, true)) {
                log.info("Tracker reminders enabled but email provider is not configured (missing tracker.reminders.resend-api-key)");
            }
            return;
        }

        Instant now = Instant.now();
        List<JobApplication> due = repo.findDueReminders(now);
        if (due.isEmpty()) return;

        int sent = 0;
        for (JobApplication job : due) {
            if (job.getUserEmail() == null || job.getUserEmail().isBlank()) continue;
            Optional<User> userOpt = userRepo.findById(job.getUserId());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (!user.isReminderEmailsEnabled()) continue;
                if (isInQuietHours(user)) continue;
            }

            boolean delivered = emailSender.sendReminder(job);
            if (!delivered) continue;

            int fallbackCadence = userOpt.map(User::getReminderFrequencyDays).orElse(3);
            int candidateCadence = job.getReminderFrequencyDays() > 0 ? job.getReminderFrequencyDays() : fallbackCadence;
            int frequencyDays = normalizeFrequency(candidateCadence);
            job.setLastReminderSentAt(now);
            job.setNextReminderAt(now.plus(frequencyDays, ChronoUnit.DAYS));
            repo.save(job);
            sent++;
        }

        if (sent > 0) {
            log.info("Sent {} tracker reminder emails", sent);
        }
    }

    private static int normalizeFrequency(int value) {
        if (value < MIN_FREQUENCY_DAYS) return MIN_FREQUENCY_DAYS;
        if (value > MAX_FREQUENCY_DAYS) return MAX_FREQUENCY_DAYS;
        return value;
    }

    private static boolean isInQuietHours(User user) {
        if (!user.isQuietHoursEnabled()) return false;
        int start = user.getQuietHoursStart();
        int end = user.getQuietHoursEnd();
        if (start == end) return false;

        ZoneId zone;
        try {
            zone = ZoneId.of(user.getReminderTimezone());
        } catch (Exception e) {
            zone = ZoneId.of("UTC");
        }
        int hour = ZonedDateTime.now(zone).getHour();
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;
    }
}
