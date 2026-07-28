Feature: SPEC-TW-006 Ticket Timeline
  As an authorized Ticket participant
  I want to view a unified Ticket Timeline
  So that I can understand the Ticket history without seeing unauthorized data

  Background:
    Given the Ticket Workflow service is healthy
    And Ticket "018f0f1e-7b31-7a00-8f42-31f9b25b1a91" exists
    And the Ticket has creation, status-history, and message records

  Scenario: Employee views the public Timeline of an owned Ticket
    Given employee "employee-123" owns the Ticket
    And the employee has the "tickets:read:self" scope
    When the employee gets the Ticket Timeline
    Then the HTTP status is 200
    And every returned item has visibility PUBLIC
    And Ticket Created, Status Changed, and public messages are visible
    And internal support notes are absent
    And the response matches the Employee Timeline schema
    And the response declares consistency SNAPSHOT

  Scenario: Employee cannot view another employee's Timeline
    Given employee "employee-999" does not own the Ticket
    And the employee has the "tickets:read:self" scope
    When the employee gets the Ticket Timeline
    Then the HTTP status is 404
    And the error code is TICKET_NOT_FOUND
    And the response does not reveal whether internal items exist

  Scenario: Support with public scope views only public items
    Given support user "support-100" is authorized for the Ticket
    And the support user has the "tickets:read:queue" scope
    But the support user does not have the "tickets:timeline:internal" scope
    When the support user gets the Ticket Timeline
    Then the HTTP status is 200
    And public items are returned
    And internal support notes are absent
    And the response view type is SUPPORT_PUBLIC_VIEW

  Scenario: Support with internal scope views internal notes
    Given support user "support-100" is authorized for the Ticket
    And the support user has the "tickets:read:queue" scope
    And the support user has the "tickets:timeline:internal" scope
    When the support user gets the Ticket Timeline
    Then the HTTP status is 200
    And public items are returned
    And authorized internal notes are returned
    And the response matches the Support Timeline schema
    And exactly one required sensitive-read Audit record is created

  Scenario: Support cannot view a Timeline outside resource scope
    Given support user "support-100" has the required Timeline scopes
    But the Ticket is outside the support user's authorized application and team scope
    When the support user gets the Ticket Timeline
    Then the HTTP status is 404
    And the error code is TICKET_NOT_FOUND
    And no Timeline body is returned

  Scenario: Client cannot request a higher privilege view
    Given employee "employee-123" owns the Ticket
    And the employee has the "tickets:read:self" scope
    When the employee sends includeInternal true or an internal-view header
    Then the HTTP status is 400
    And the error code is VALIDATION_ERROR
    And no internal item is returned

  Scenario: Timeline ordering is deterministic
    Given Timeline items have different occurredAt values
    When an authorized actor gets the Timeline
    Then items are ordered by occurredAt ascending
    And then by itemTypeRank ascending
    And then by itemId ascending

  Scenario: Equal timestamps use type rank and item ID
    Given multiple Timeline items have the same occurredAt
    When an authorized actor gets the Timeline
    Then TICKET_CREATED appears before STATUS_CHANGED
    And STATUS_CHANGED appears before message items
    And items with equal type rank are ordered by itemId
    And pagination contains no duplicate

  Scenario: First page creates a fixed snapshot
    Given an authorized actor requests the first Timeline page
    When the page is returned
    Then the response contains snapshotAt
    And consistency is SNAPSHOT
    And the next cursor contains the same signed snapshot boundary

  Scenario: New item after snapshot does not enter later pages
    Given the actor received the first page with a valid cursor
    And a new public message is added after snapshotAt
    When the actor requests the next page using the original cursor
    Then the new message is absent from the old snapshot
    And no item from the first page is repeated
    When the actor refreshes without the old cursor
    Then the new message is visible in the new snapshot

  Scenario: Support internal note added after snapshot does not enter later pages
    Given an authorized internal Support actor received the first page
    And an internal note is created after snapshotAt
    When the actor reads the next page using the original cursor
    Then the internal note is absent from the old snapshot

  Scenario: Cursor cannot be reused for another Ticket
    Given an authorized actor received a cursor for the Ticket
    When the actor uses the cursor for a different Ticket
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Cursor cannot be reused after the view changes
    Given a Support actor received a SUPPORT_INTERNAL_VIEW cursor
    And the internal Timeline scope is removed
    When the actor uses the old cursor
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Tampered cursor is rejected
    Given an authorized actor received a valid Timeline cursor
    And one byte of the cursor is modified
    When the actor requests the next page
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR
    And no cursor payload is returned

  Scenario: Expired cursor is rejected
    Given the Timeline cursor is older than 24 hours
    When the actor requests the next page
    Then the HTTP status is 400
    And the error code is INVALID_CURSOR

  Scenario: Employee item fields are safely redacted
    Given employee "employee-123" owns the Ticket
    When the employee gets the Timeline
    Then the response does not contain actorId
    And the response does not contain authorRef
    And the response does not contain internalReasonCode
    And the response does not contain workflowId
    And the response does not contain audit metadata
    And the response does not contain internal notes

  Scenario: Raw Audit records are not exposed as Timeline items
    Given the Ticket has Business Audit and Security Audit records
    When an authorized actor gets the Timeline
    Then raw Audit records are not returned as Timeline items
    And no Audit payload is present in the response

  Scenario: Required sensitive-read Audit failure closes the response
    Given support user "support-100" is authorized for SUPPORT_INTERNAL_VIEW
    And the required Timeline Audit insert fails
    When the support user gets the Timeline
    Then the HTTP status is 500
    And the error code is INTERNAL_ERROR
    And no sensitive Timeline body is returned

  Scenario: Existing Ticket with no Timeline source returns an empty list
    Given an authorized migration Ticket has no Timeline source records
    When the actor gets the Timeline
    Then the HTTP status is 200
    And items is empty
    And hasMore is false
    And nextCursor is null

  Scenario: Timeline query does not mutate business state
    Given Ticket, History, and Message records are captured before the query
    When an authorized actor gets the Timeline
    Then the Ticket status is unchanged
    And the Ticket version is unchanged
    And the Ticket updatedAt is unchanged
    And no History or Message record is modified
    And no Outbox record is created

  Scenario: Timeline telemetry excludes sensitive content
    Given an authorized actor gets a Timeline containing messages
    When logs, traces, metrics, and Audit records are inspected
    Then none contains full message content
    And none contains a raw cursor
    And none contains a JWT
    And no metric label contains Ticket ID or Item ID
