Feature: SPEC-TW-001 Create Ticket
  As an authenticated employee
  I want to create an IT support ticket
  So that OpsMind can begin a controlled support workflow

  Background:
    Given the Ticket Workflow service is healthy
    And the employee is authenticated
    And the employee has the "tickets:create" scope
    And a valid local SLA policy exists

  Scenario: Create a valid Ticket
    Given a unique Idempotency-Key
    And a valid Create Ticket request
    When the employee creates the Ticket
    Then the HTTP status is 201
    And the Location header points to the created Ticket
    And the ETag is "0"
    And exactly one Ticket exists with status "NEW"
    And its priority is "UNASSIGNED"
    And its requester is the authenticated employee
    And exactly one active Resolution Cycle exists with cycle number 1
    And exactly one active SLA Cycle exists with cycle number 1
    And exactly one initial Status History record exists for "SM-001"
    And exactly one "TICKET_CREATED" Business Audit record exists
    And exactly one "ticket.created.v1" Outbox record exists
    And the Idempotency Record is "COMPLETED"

  Scenario: Replay the same completed request
    Given a completed Create Ticket request
    When the same employee sends the same payload with the same Idempotency-Key
    Then the original HTTP status is returned
    And the original Ticket response is returned
    And the response contains "Idempotency-Replayed: true"
    And no second Ticket is created
    And no second Resolution Cycle is created
    And no second SLA Cycle is created
    And no duplicate History, Audit, or Outbox record is created

  Scenario: Reject reuse of the key with a different payload
    Given a completed Create Ticket request
    When the same employee sends a different payload with the same Idempotency-Key
    Then the HTTP status is 409
    And the error code is "IDEMPOTENCY_KEY_REUSED"
    And no new Ticket is created

  Scenario: Return request in progress for a fresh reservation
    Given another request holds a fresh IN_PROGRESS record for the same actor and key
    When the employee sends the same request
    Then the HTTP status is 409
    And the error code is "REQUEST_IN_PROGRESS"
    And the Retry-After header is "1"
    And no second Ticket is created

  Scenario: Reject a missing Idempotency-Key
    Given a valid Create Ticket request
    But the Idempotency-Key header is missing
    When the employee creates the Ticket
    Then the HTTP status is 400
    And the error code is "VALIDATION_ERROR"
    And no Ticket is created

  Scenario: Reject requester identity injection
    Given a request body containing a requesterId field
    When the employee creates the Ticket
    Then the HTTP status is 400
    And the error code is "VALIDATION_ERROR"
    And no Ticket is created

  Scenario: Reject an employee without create scope
    Given the employee does not have the "tickets:create" scope
    And a valid Create Ticket request
    When the employee creates the Ticket
    Then the HTTP status is 403
    And the error code is "FORBIDDEN"
    And no Ticket is created

  Scenario: Roll back when the SLA Cycle insert fails
    Given a unique Idempotency-Key
    And a valid Create Ticket request
    And the SLA Cycle insert fails
    When the employee creates the Ticket
    Then the HTTP status is 500
    And no Ticket remains committed
    And no Resolution Cycle remains committed
    And no Status History remains committed
    And no Audit record remains committed
    And no Outbox record remains committed
    And no successful Idempotency response is stored

  Scenario: Roll back when the Audit insert fails
    Given a unique Idempotency-Key
    And a valid Create Ticket request
    And the required Audit insert fails
    When the employee creates the Ticket
    Then the HTTP status is 500
    And no Ticket remains committed
    And no Resolution Cycle remains committed
    And no SLA Cycle remains committed
    And no Status History remains committed
    And no Outbox record remains committed
    And no successful Idempotency response is stored

  Scenario: Roll back when the Outbox insert fails
    Given a unique Idempotency-Key
    And a valid Create Ticket request
    And the Outbox insert fails
    When the employee creates the Ticket
    Then the HTTP status is 500
    And no Ticket remains committed
    And no Resolution Cycle remains committed
    And no SLA Cycle remains committed
    And no Status History remains committed
    And no Audit record remains committed
    And no successful Idempotency response is stored

  Scenario: Concurrent duplicate requests create exactly one Ticket
    Given 100 concurrent requests from the same employee
    And every request uses the same Idempotency-Key
    And every request has the same payload
    When all requests complete
    Then exactly one Ticket exists
    And all successful responses reference the same Ticket
    And other concurrent responses are stable replays or REQUEST_IN_PROGRESS
    And exactly one ticket.created Outbox event exists

  Scenario: The integration event minimizes sensitive data
    Given a Ticket is created successfully
    When the ticket.created.v1 Outbox payload is inspected
    Then it contains displayId, requesterIdHash, applicationCode, source, initialStatus, and createdAt
    And it does not contain title
    And it does not contain description
    And it does not contain raw requesterId
    And it does not contain requester email
    And it does not contain a JWT
    And it does not contain an Idempotency-Key
