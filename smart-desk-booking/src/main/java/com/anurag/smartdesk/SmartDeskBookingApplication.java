package com.anurag.smartdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartDeskBookingApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartDeskBookingApplication.class, args);
	}

}
