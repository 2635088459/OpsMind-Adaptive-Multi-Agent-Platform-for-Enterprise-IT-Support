package com.opsmind.policygovernance.infrastructure.notification;

import com.opsmind.policygovernance.config.GovernanceNotificationProperties;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.approval.ApprovalType;
import com.opsmind.policygovernance.domain.decision.Constraint;
import com.opsmind.policygovernance.domain.decision.RiskLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SPEC-XOBS-001 Part A: SmtpApprovalNotificationAdapter addresses the right mailbox
 * per lifecycle phase and never lets a mail failure escape (the approval transaction
 * has already committed by the time the port is called).
 */
@Tag("unit")
class SmtpApprovalNotificationAdapterTest {

    private final GovernanceNotificationProperties properties = new GovernanceNotificationProperties(
        "smtp", "it-governance@opsmind.dev", "it-approvers@opsmind.dev", "opsmind.dev"
    );

    private static ApprovalRequest request(String requestedBy) {
        return ApprovalRequest.requested(
            "ar-11111111-2222-3333-4444-555555555555", "rk-1", "hash-1",
            "agent-runtime", "src-1", "ticket-9", "wf-9", "tool-9", null, "pd-9",
            requestedBy, ApprovalType.TOOL_EXECUTION, RiskLevel.HIGH,
            List.of(new Constraint(Constraint.Type.TIME_WINDOW, "business hours only")),
            Instant.parse("2026-09-10T00:00:00Z"), Instant.parse("2026-09-09T12:00:00Z")
        );
    }

    @Test
    void notifyRequested_emails_the_configured_approver_mailbox() {
        MailSender mailSender = mock(MailSender.class);
        new SmtpApprovalNotificationAdapter(mailSender, properties).notifyRequested(request("test.agent"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("it-approvers@opsmind.dev");
        assertThat(sent.getFrom()).isEqualTo("it-governance@opsmind.dev");
        assertThat(sent.getSubject()).contains("Approval required", "HIGH", "TOOL_EXECUTION");
        assertThat(sent.getText()).contains("ar-11111111-2222-3333-4444-555555555555", "TIME_WINDOW=business hours only");
    }

    @Test
    void notifyDecided_derives_the_requester_address_from_a_bare_username() {
        MailSender mailSender = mock(MailSender.class);
        new SmtpApprovalNotificationAdapter(mailSender, properties).notifyDecided(request("test.agent"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("test.agent@opsmind.dev");
        // Subject encodes the request's current status (REQUESTED for this bare unit
        // fixture; APPROVED/DENIED/CANCELLED once ApprovalService has decided it).
        assertThat(captor.getValue().getSubject()).contains("Approval", "REQUESTED");
    }

    @Test
    void notifyDecided_uses_an_already_qualified_address_verbatim() {
        MailSender mailSender = mock(MailSender.class);
        new SmtpApprovalNotificationAdapter(mailSender, properties).notifyDecided(request("someone@example.org"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("someone@example.org");
    }

    @Test
    void a_mail_failure_is_swallowed_not_propagated() {
        MailSender mailSender = mock(MailSender.class);
        doThrow(new MailSendException("mailpit unreachable")).when(mailSender).send(any(SimpleMailMessage.class));

        SmtpApprovalNotificationAdapter adapter = new SmtpApprovalNotificationAdapter(mailSender, properties);
        assertThatCode(() -> adapter.notifyRequested(request("test.agent"))).doesNotThrowAnyException();
        assertThatCode(() -> adapter.notifyDecided(request("test.agent"))).doesNotThrowAnyException();
    }
}
