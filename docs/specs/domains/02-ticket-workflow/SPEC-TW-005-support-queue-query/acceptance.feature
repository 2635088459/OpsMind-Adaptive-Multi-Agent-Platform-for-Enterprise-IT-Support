Feature: SPEC-TW-005 Support Queue Query
  As an authorized IT Support user
  I want to query the Ticket Queue within my support scope
  So that I can work on the most urgent authorized Tickets first

  Background:
    Given the Ticket Workflow service is healthy
    And support user "support-100" is authenticated
    And the support user has the "tickets:read:queue" scope
    And the support user is authorized for application "HOUSING_PORTAL"
    And the support user is authorized for team "TEAM-HOUSING"

  Scenario: Support reads the default operational queue
    Given authorized non-terminal Tickets exist
    And CLOSED and CANCELLED Tickets also exist
    When the support user queries the default Queue
    Then the HTTP status is 200
    And only authorized non-terminal Tickets are returned
    And CLOSED and CANCELLED Tickets are absent
    And each item matches the Support Ticket Summary schema
    And the response matches the Support Queue schema

  Scenario: Queue authorization is enforced in SQL
    Given authorized and unauthorized application Tickets exist
    When the support user queries the Queue
    Then every returned Ticket belongs to the authorized application and team scope
    And unauthorized rows are not loaded for application-side filtering

  Scenario: Requested application filter must be within scope
    When the support user filters by applicationCode "VPN"
    But the support user is not authorized for "VPN"
    Then the HTTP status is 403
    And the error code is FILTER_OUTSIDE_AUTHORIZED_SCOPE
    And no Ticket data is returned

  Scenario: Filter by priority and SLA state
    Given authorized Tickets with different priorities and SLA states exist
    When the support user filters by priority P1 and slaState AT_RISK
    Then every returned Ticket has priority P1
    And every returned Ticket has SLA state AT_RISK

  Scenario: Query only unassigned Tickets
    Given assigned and unassigned authorized Tickets exist
    When the support user queries with unassignedOnly true
    Then every returned Ticket has no assigned agent

  Scenario: Conflicting assignment filters are rejected
    When the support user queries with unassignedOnly true and assignedAgent "agent-200"
    Then the HTTP status is 400
    And the error code is VALIDATION_ERROR

  Scenario: Default queue ordering is deterministic
    Given authorized Tickets have different SLA ranks, priorities, and creation times
    When the support user queries the Queue
    Then Tickets are ordered by SLA rank ascending
    And then by priority rank ascending
    And then by createdAt ascending
    And then by ticketId ascending

  Scenario: Ticket ID resolves equal sort keys
    Given several Tickets have equal SLA rank, priority rank, and createdAt
    When the support user queries the Queue
    Then those Tickets are ordered by ticketId ascending
    And keyset pagination contains no duplicate for unchanged records

  Scenario: SLA evaluation time remains fixed across pages
    Given the support user reads the first page at evaluation time "2026-07-25T19:00:00Z"
    And real time advances before the second page request
    When the support user reads the second page using the original cursor
    Then SLA urgency is evaluated using "2026-07-25T19:00:00Z"
    And time passage alone does not reorder the remaining rows

  Scenario: Support reads the next queue page
    Given the support user received a valid Queue cursor
    When the support user sends the cursor
    Then only rows after the previous keyset boundary are returned
    And unchanged Tickets from the first page are not repeated

  Scenario: Cursor cannot be reused after scope changes
    Given the support user received a valid Queue cursor
    And the support user's authorized team scope changes
    When the support user sends the old cursor
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Cursor cannot be reused with different filters
    Given the support user received a cursor for priority P1
    When the support user reuses it with priority P2
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Tampered Queue cursor is rejected
    Given the support user received a valid Queue cursor
    And the cursor payload is modified
    When the support user queries the Queue
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR
    And no cursor internals are returned

  Scenario: Expired Queue cursor is rejected
    Given the Queue cursor is older than one hour
    When the support user queries the Queue
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Empty Queue returns success
    Given no authorized Ticket matches the filters
    When the support user queries the Queue
    Then the HTTP status is 200
    And items is empty
    And hasMore is false
    And nextCursor is null

  Scenario: Employee cannot access the Support Queue
    Given employee "employee-123" is authenticated
    And the employee has only employee Ticket scopes
    When the employee queries the Support Queue
    Then the HTTP status is 403
    And the error code is FORBIDDEN

  Scenario: Queue summary minimizes sensitive data
    Given the Queue contains at least one Ticket
    When the support user queries the Queue
    Then the response contains minimal summary fields
    And the response does not contain full description
    And the response does not contain message content
    And the response does not contain internal note content
    And the response does not contain requester email
    And the response does not contain tool credentials
    And the response does not contain full audit metadata

  Scenario: Queue query does not mutate Ticket state
    Given Ticket statuses, versions, and updatedAt values are recorded
    When the support user queries the Queue
    Then every Ticket status is unchanged
    And every Ticket version is unchanged
    And every Ticket updatedAt is unchanged
    And no Status History record is created
    And no Outbox record is created
