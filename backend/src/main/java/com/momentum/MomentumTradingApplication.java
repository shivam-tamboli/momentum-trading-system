package com.momentum;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MomentumTradingApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
                .directory("./")
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e ->
                System.setProperty(e.getKey(), e.getValue()));

        SpringApplication.run(MomentumTradingApplication.class, args);
    }

}
