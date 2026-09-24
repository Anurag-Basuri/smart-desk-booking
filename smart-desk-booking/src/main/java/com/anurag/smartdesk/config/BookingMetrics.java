package com.anurag.smartdesk.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

// Custom Micrometer metrics for booking domain events.
//
// WHY CUSTOM METRICS?
// Spring Boot Actuator gives us basic JVM/HTTP metrics for free.
// But the business team wants to know:
//   - How many bookings are being made? (booking.requests.total)
//   - How many conflict rejections happen? (booking.conflicts.total)
//   - How long does the allocation algorithm take? (booking.allocations.latency)
//   - How many no-shows are being auto-released? (booking.noshow.releases.total)
//
// These metrics can be scraped by Prometheus and displayed in Grafana.
@Component
public class BookingMetrics {

    private final Counter bookingRequestsTotal;
    private final Counter bookingConflictsTotal;
    private final Counter noShowReleasesTotal;
    private final Timer allocationLatency;

    public BookingMetrics(MeterRegistry registry) {
        this.bookingRequestsTotal = Counter.builder("booking.requests.total")
                .description("Total number of booking requests")
                .register(registry);

        this.bookingConflictsTotal = Counter.builder("booking.conflicts.total")
                .description("Total booking conflicts (quota, capacity, duplicate)")
                .register(registry);

        this.noShowReleasesTotal = Counter.builder("booking.noshow.releases.total")
                .description("Total bookings auto-released due to no-show")
                .register(registry);

        this.allocationLatency = Timer.builder("booking.allocations.latency")
                .description("Time taken by the desk allocation algorithm")
                .register(registry);
    }

    public void incrementBookingRequests() {
        bookingRequestsTotal.increment();
    }

    public void incrementConflicts() {
        bookingConflictsTotal.increment();
    }

    public void incrementNoShowReleases(int count) {
        noShowReleasesTotal.increment(count);
    }

    public Timer getAllocationLatency() {
        return allocationLatency;
    }
}
