package com.anurag.smartdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// Provides a system Clock bean for dependency injection.
//
// Why inject Clock instead of calling Instant.now() directly?
// In production, this gives us the real system time.
// In tests, we can replace it with a fixed Clock to control time
// (e.g., test "what happens if a booking is made at 11 PM?")
// without waiting for the actual time to pass.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
