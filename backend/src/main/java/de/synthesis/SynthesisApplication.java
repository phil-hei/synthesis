package de.synthesis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SynthesisApplication {

    public static void main(String[] args) {
        SpringApplication.run(SynthesisApplication.class, args);
    }
}
