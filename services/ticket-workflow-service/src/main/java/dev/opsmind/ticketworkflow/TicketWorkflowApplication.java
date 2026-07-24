package dev.opsmind.ticketworkflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TicketWorkflowApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketWorkflowApplication.class, args);
    }
}
