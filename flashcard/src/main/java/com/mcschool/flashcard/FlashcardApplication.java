package com.mcschool.flashcard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlashcardApplication {

	public static void main(String[] args) {
		SpringApplication.run(FlashcardApplication.class, args);
	}

}
