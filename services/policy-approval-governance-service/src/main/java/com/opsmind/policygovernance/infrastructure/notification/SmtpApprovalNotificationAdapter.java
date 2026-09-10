package com.opsmind.policygovernance.infrastructure.notification;

import com.opsmind.policygovernance.application.port.ApprovalNotificationPort;
import com.opsmind.policygovernance.config.GovernanceNotificationProperties;
import com.opsmind.policygovernance.domain.approval.ApprovalRequest;
import com.opsmind.policygovernance.domain.decision.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * SPEC-XOBS-001 Part A: the real {@link ApprovalNotificationPort} — sends a plain-text
 * email per approval-lifecycle change through Spring's {@link MailSender}
 * ({@code spring.mail.*}; Mailpit on {@code :1025} in the local stack). Active only when
 * {@code opsmind.governance.notifications.mode=smtp}.
 *
 * <p><b>Best-effort, never throws.</b> {@link ApprovalNotificationPort}'s own javadoc
 * — "out-of-band of the governance fact stream" — plus the fact that
 * {@code ApprovalService} calls these methods <em>after</em> the approval transaction's
 * state change is already saved: a {@link MailException} (broker down, bad address)
 * must not propagate and roll that back. Failures are logged at WARN and dropped, the
 * same best-effort posture user-access-authentication-service takes for its IdP
 * end-session notification.
 *
 * <p><b>Addressing.</b> A new request goes to the single configured approver mailbox
 * (governance has no approver directory). A decision goes to the requester, resolved
 * from {@code requestedBy} by {@link GovernanceNotificationProperties#requesterAddress}.
 */
@Component
@ConditionalOnProperty(prefix = "opsmind.governance.notifications", name = "mode", havingValue = "smtp")
public class SmtpApprovalNotificationAdapter implements ApprovalNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(SmtpApprovalNotificationAdapter.class);

    private final MailSender mailSender;
    private final GovernanceNotificationProperties properties;

    public SmtpApprovalNotificationAdapter(MailSender mailSender, GovernanceNotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void notifyRequested(ApprovalRequest approvalRequest) {
        String subject = "[OpsMind] Approval required: %s %s%s".formatted(
            approvalRequest.riskLevel(), approvalRequest.approvalType(), ticketSuffix(approvalRequest)
        );
        send(properties.approverAddress(), subject, requestedBody(approvalRequest), approvalRequest.approvalRequestId(), "requested");
    }

    @Override
    public void notifyDecided(ApprovalRequest approvalRequest) {
        String recipient = properties.requesterAddress(approvalRequest.requestedBy());
        String subject = "[OpsMind] Approval %s: %s%s".formatted(
            approvalRequest.status(), shortId(approvalRequest.approvalRequestId()), ticketSuffix(approvalRequest)
        );
        send(recipient, subject, decidedBody(approvalRequest), approvalRequest.approvalRequestId(), "decided");
    }

    private void send(String to, String subject, String body, String approvalRequestId, String phase) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.fromAddress());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            log.info("approval {} notification sent approvalRequestId={} to={}", phase, approvalRequestId, to);
        } catch (MailException e) {
            // Best-effort: the approval state change already committed. Do not rethrow.
            log.warn("approval {} notification failed approvalRequestId={} to={}: {}", phase, approvalRequestId, to, e.getMessage());
        }
    }

    private static String requestedBody(ApprovalRequest r) {
        return """
            An approval request is waiting for your decision.

            Approval request : %s
            Risk level       : %s
            Type             : %s
            Source           : %s / %s
            Ticket           : %s
            Workflow         : %s
            Tool request     : %s
            Requested by     : %s
            Expires at       : %s
            Conditions       : %s

            Decide it in the Support Console approvals view.
            """.formatted(
            r.approvalRequestId(), r.riskLevel(), r.approvalType(),
            nullToDash(r.sourceDomain()), nullToDash(r.sourceRequestId()),
            nullToDash(r.ticketId()), nullToDash(r.workflowInstanceId()), nullToDash(r.toolRequestId()),
            nullToDash(r.requestedBy()), nullToDash(String.valueOf(r.expiresAt())), constraints(r)
        );
    }

    private static String decidedBody(ApprovalRequest r) {
        return """
            Your approval request has been %s.

            Approval request : %s
            Risk level       : %s
            Type             : %s
            Source           : %s / %s
            Ticket           : %s
            Workflow         : %s
            Tool request     : %s

            No further action is required for this request.
            """.formatted(
            r.status(), r.approvalRequestId(), r.riskLevel(), r.approvalType(),
            nullToDash(r.sourceDomain()), nullToDash(r.sourceRequestId()),
            nullToDash(r.ticketId()), nullToDash(r.workflowInstanceId()), nullToDash(r.toolRequestId())
        );
    }

    private static String constraints(ApprovalRequest r) {
        if (r.constraints() == null || r.constraints().isEmpty()) {
            return "(none)";
        }
        return r.constraints().stream()
            .map(c -> "%s=%s".formatted(constraintType(c), c.detail()))
            .collect(Collectors.joining("; "));
    }

    private static String constraintType(Constraint c) {
        return c.type() == null ? "?" : c.type().name();
    }

    private static String ticketSuffix(ApprovalRequest r) {
        return (r.ticketId() == null || r.ticketId().isBlank()) ? "" : " for ticket " + r.ticketId();
    }

    private static String shortId(String id) {
        return (id == null || id.length() < 8) ? String.valueOf(id) : id.substring(0, 8);
    }

    private static String nullToDash(String value) {
        return (value == null || value.isBlank() || "null".equals(value)) ? "-" : value;
    }
}
