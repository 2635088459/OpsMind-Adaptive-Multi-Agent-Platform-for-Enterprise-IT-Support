Feature: SPEC-TW-003 List Requester Tickets
  As an authenticated employee
  I want to list the Tickets I created
  So that I can review my support requests safely and consistently

  Background:
    Given the Ticket Workflow service is healthy
    And employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope

  Scenario: Employee gets the first page
    Given employee "employee-123" owns 25 Tickets
    When the employee lists Tickets with limit 20
    Then the HTTP status is 200
    And 20 Ticket summaries are returned
    And every Ticket belongs to "employee-123"
    And the Tickets are sorted by createdAt descending and ticketId descending
    And hasMore is true
    And nextCursor is present

  Scenario: Employee gets the next page
    Given the employee received a valid cursor from the first page
    When the employee lists Tickets with that cursor
    Then only Tickets after the previous page boundary are returned
    And no Ticket from the first page is repeated

  Scenario: Empty list returns success
    Given the employee owns no Tickets
    When the employee lists Tickets
    Then the HTTP status is 200
    And items is empty
    And hasMore is false
    And nextCursor is null

  Scenario: Filter by status and application
    Given the employee owns Tickets with different statuses and applications
    When the employee filters by status NEW and applicationCode VPN
    Then every returned Ticket has status NEW
    And every returned Ticket has applicationCode VPN

  Scenario: Filter by creation range
    Given the employee owns Tickets before, within, and after the requested range
    When the employee sends createdFrom and createdTo
    Then returned Tickets satisfy createdAt greater than or equal to createdFrom
    And returned Tickets satisfy createdAt less than createdTo

  Scenario: Equal creation times use Ticket ID as a tie-breaker
    Given several Tickets have the same createdAt
    When the employee lists Tickets
    Then those Tickets are ordered by ticketId descending
    And pagination contains no duplicate

  Scenario: Cursor cannot be reused with different filters
    Given the employee received a cursor for status NEW
    When the employee reuses it with status RESOLVED
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Tampered cursor is rejected
    Given the employee received a valid cursor
    And the cursor is modified
    When the employee lists Tickets
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR
    And no cursor internals are returned

  Scenario: Expired cursor is rejected
    Given the cursor is older than its TTL
    When the employee lists Tickets
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Another employee cannot reuse the cursor
    Given employee "employee-123" received a valid cursor
    And employee "employee-999" is authenticated
    When employee "employee-999" uses the cursor
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Other users' Tickets never appear
    Given employee "employee-123" and employee "employee-999" both own Tickets
    When employee "employee-123" lists Tickets
    Then every returned Ticket belongs to "employee-123"

  Scenario: New Ticket insertion between pages causes no duplicate
    Given the employee reads the first page
    And a newer Ticket is created
    When the employee reads the next page with the original cursor
    Then no Ticket from the first page is repeated
    And the new Ticket does not appear in the old cursor's later pages

  Scenario: Limit above maximum is rejected
    When the employee lists Tickets with limit 100
    Then the HTTP status is 400
    And the error code is VALIDATION_ERROR

  Scenario: List response minimizes sensitive data
    Given the employee owns at least one Ticket
    When the employee lists Tickets
    Then the response does not contain description
    And the response does not contain requesterId
    And the response does not contain internal assignment IDs
    And the response does not contain workflow or audit metadata

  Scenario: List query does not mutate business state
    Given Ticket versions and updatedAt values are recorded
    When the employee lists Tickets
    Then every Ticket version is unchanged
    And every Ticket updatedAt is unchanged
    And no Status History record is created
    And no Outbox record is created
