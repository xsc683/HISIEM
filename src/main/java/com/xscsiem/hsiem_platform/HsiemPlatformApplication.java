package com.xscsiem.hsiem_platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HsiemPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(HsiemPlatformApplication.class, args);
	}

}
