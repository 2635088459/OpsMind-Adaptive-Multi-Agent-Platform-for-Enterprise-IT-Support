package dev.opsmind.ticketworkflow.ticket.application.port.in;

import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeCommand;
import dev.opsmind.ticketworkflow.ticket.application.command.UserReplyAndResumeResult;

public interface UserReplyAndResumeUseCase {

    UserReplyAndResumeResult reply(UserReplyAndResumeCommand command);
}
