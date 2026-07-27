Feature: SPEC-TW-004 Add Ticket Message
  As an authorized Ticket participant
  I want to append a message to a Ticket
  So that communication is preserved safely and consistently

  Background:
    Given the Ticket Workflow service is healthy
    And Ticket "018f0f1e-7b31-7a00-8f42-31f9b25b1a91" exists
    And the Ticket status is "INVESTIGATING"

  Scenario: Employee adds a public requester message
    Given employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    And a unique Idempotency-Key
    When the employee posts valid message content
    Then the HTTP status is 201
    And exactly one PUBLIC_REQUESTER_MESSAGE exists
    And its visibility is PUBLIC
    And its author is derived from the employee principal
    And one Business Audit record exists
    And one ticket.message.added.v1 Outbox record exists
    And the Idempotency Record is COMPLETED

  Scenario: Support adds a public support message
    Given support user "support-100" is authorized for the Ticket
    And the support user has the "tickets:message:public" scope
    And a unique Idempotency-Key
    When the support user posts a PUBLIC_SUPPORT_MESSAGE
    Then the HTTP status is 201
    And the message visibility is PUBLIC
    And the author is derived from the support principal

  Scenario: Support adds an internal note
    Given support user "support-100" is authorized for the Ticket
    And the support user has the "tickets:message:internal" scope
    And a unique Idempotency-Key
    When the support user posts an INTERNAL_SUPPORT_NOTE
    Then the HTTP status is 201
    And the message visibility is INTERNAL
    And the message is not visible through Employee APIs
    And the Audit action is TICKET_INTERNAL_NOTE_ADDED

  Scenario: Employee cannot inject an internal note
    Given employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee request contains messageType or visibility
    Then the HTTP status is 400
    And the error code is VALIDATION_ERROR
    And no Message is created

  Scenario: Employee cannot write to another user's Ticket
    Given employee "employee-999" does not own the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee posts valid message content
    Then the HTTP status is 404
    And the error code is TICKET_NOT_FOUND
    And the response does not reveal that the Ticket exists

  Scenario Outline: Terminal Ticket rejects messages
    Given the Ticket status is "<status>"
    And employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee posts valid message content
    Then the HTTP status is 409
    And the error code is MESSAGE_NOT_ALLOWED_IN_STATE
    And no Message is created

    Examples:
      | status    |
      | CLOSED    |
      | CANCELLED |

  Scenario: Resolved Ticket accepts feedback without reopening
    Given the Ticket status is RESOLVED
    And employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee posts valid message content
    Then the HTTP status is 201
    And the Ticket status remains RESOLVED
    And no reopened event is created

  Scenario: Waiting-for-user message does not auto-transition in Phase 02
    Given the Ticket status is WAITING_FOR_USER
    And employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee posts valid message content
    Then the HTTP status is 201
    And the Ticket status remains WAITING_FOR_USER
    And no workflow-resume event is created

  Scenario: Secret-containing content is rejected
    Given employee "employee-123" owns the Ticket
    And the employee has the "tickets:message:self" scope
    When the employee posts content containing a private key
    Then the HTTP status is 400
    And the error code is VALIDATION_ERROR
    And the error does not echo the private key
    And no Message is created

  Scenario: Replay returns the original message
    Given a completed Add Ticket Message request
    When the same actor sends the same content with the same Idempotency-Key
    Then the original HTTP status and response are returned
    And Idempotency-Replayed is true
    And no second Message, Audit, or Outbox record is created

  Scenario: Same key with different content is rejected
    Given a completed Add Ticket Message request
    When the same actor sends different content with the same Idempotency-Key
    Then the HTTP status is 409
    And the error code is IDEMPOTENCY_KEY_REUSED
    And no second Message is created

  Scenario: Concurrent duplicate requests create exactly one message
    Given 100 concurrent requests from the same actor
    And every request uses the same Idempotency-Key and content
    When all requests complete
    Then exactly one Message exists
    And exactly one Audit record exists
    And exactly one ticket.message.added.v1 Outbox event exists

  Scenario: Audit insert failure rolls back everything
    Given an authorized valid message request
    And the required Audit insert fails
    When the actor adds the message
    Then no Message remains committed
    And no Outbox record remains committed
    And no completed Idempotency response is stored

  Scenario: Outbox insert failure rolls back everything
    Given an authorized valid message request
    And the Outbox insert fails
    When the actor adds the message
    Then no Message remains committed
    And no Audit record remains committed
    And no completed Idempotency response is stored

  Scenario: Message command does not mutate Ticket lifecycle
    Given the Ticket status, version, and updatedAt are recorded
    And an authorized actor posts a valid message
    When the command completes
    Then the Ticket status is unchanged
    And the Ticket version is unchanged
    And the Ticket updatedAt is unchanged
    And no Status History record is created

  Scenario: Event and telemetry minimize sensitive data
    Given a message is created successfully
    When the Outbox event, Audit record, logs, and traces are inspected
    Then none contains the full message content
    And none contains a JWT
    And none contains an Idempotency-Key
    And the event contains messageId, ticketId, messageType, visibility, authorType, and createdAt
