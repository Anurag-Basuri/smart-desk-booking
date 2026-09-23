package com.anurag.smartdesk.scheduler;

import com.anurag.smartdesk.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Background job that automatically releases desks of employees
// who didn't check in before their deadline.
//
// Runs every 60 seconds. The underlying SQL query is idempotent,
// so even if multiple app instances run this simultaneously,
// the result is the same — no duplicate updates or race conditions.
@Component
public class NoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            NoShowScheduler.class);

    private final BookingService bookingService;

    public NoShowScheduler(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    // fixedRate = 60000 means "run every 60 seconds"
    // The sweep checks: any booking still in BOOKED status
    // whose check-in deadline has passed? → mark it NO_SHOW.
    @Scheduled(fixedRate = 60000)
    public void sweepNoShows() {
        try {
            int count = bookingService.sweepNoShows();
            if (count > 0) {
                log.info("No-show sweep completed: {} bookings released",
                        count);
            }
        } catch (Exception e) {
            // Log but don't crash — the next run will retry.
            // A scheduler failure should never bring down the app.
            log.error("No-show sweep failed: {}", e.getMessage(), e);
        }
    }
}
