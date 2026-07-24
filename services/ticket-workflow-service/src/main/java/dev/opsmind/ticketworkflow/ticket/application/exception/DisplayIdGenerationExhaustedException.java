package dev.opsmind.ticketworkflow.ticket.application.exception;

public class DisplayIdGenerationExhaustedException extends RuntimeException {

    public DisplayIdGenerationExhaustedException(int attempts) {
        super("could not generate a unique display id after " + attempts + " attempts");
    }
}
