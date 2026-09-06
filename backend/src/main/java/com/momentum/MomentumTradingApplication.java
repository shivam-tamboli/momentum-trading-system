package com.momentum;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MomentumTradingApplication {

    public static void main(String[] args) {
        // Without this, the JVM's default dual-stack behavior tries IPv6 first — observed hanging
        // for 10+ seconds connecting to raw.githubusercontent.com in this environment (a plain
        // curl to the same URL resolves in well under a second), before ever falling back to
        // IPv4. Must be set before any networking classes are touched, so it's the first thing
        // main() does.
        System.setProperty("java.net.preferIPv4Stack", "true");

        Dotenv dotenv = Dotenv.configure()
                .directory("./")
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(e ->
                System.setProperty(e.getKey(), e.getValue()));

        SpringApplication.run(MomentumTradingApplication.class, args);
    }

}
