Feature: SPEC-TW-002 Get Ticket
  As an authenticated OpsMind actor
  I want to read one Ticket
  So that I can see the current information I am authorized to access

  Background:
    Given the Ticket Workflow service is healthy
    And Ticket "018f0f1e-7b31-7a00-8f42-31f9b25b1a91" exists
    And its current version is 0

  Scenario: Employee reads an owned Ticket
    Given employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope
    And the Ticket requester is "employee-123"
    When the employee gets the Ticket
    Then the HTTP status is 200
    And the ETag is "0"
    And Cache-Control is "private, no-store"
    And the response matches the Employee Ticket schema
    And the response contains the Ticket title and description
    And the response does not contain requesterRef
    And the response does not contain internal assignment IDs
    And the response does not contain workflow metadata
    And the response does not contain audit metadata

  Scenario: Employee cannot read another employee's Ticket
    Given employee "employee-999" is authenticated
    And the employee has the "tickets:read:self" scope
    And the Ticket requester is "employee-123"
    When the employee gets the Ticket
    Then the HTTP status is 404
    And the error code is "TICKET_NOT_FOUND"
    And the response does not reveal that the Ticket exists

  Scenario: Support reads a Ticket in an authorized queue
    Given support user "support-100" is authenticated
    And the support user has the "tickets:read:queue" scope
    And the support user is authorized for application "HOUSING_PORTAL"
    And the Ticket application is "HOUSING_PORTAL"
    When the support user gets the Ticket
    Then the HTTP status is 200
    And the response matches the Support Ticket schema
    And the response contains requesterRef
    And the response contains assignment and SLA summaries
    And one required sensitive-read Audit record is created

  Scenario: Support cannot read a Ticket outside authorized scope
    Given support user "support-100" is authenticated
    And the support user has the "tickets:read:queue" scope
    And the support user is authorized only for application "VPN"
    And the Ticket application is "HOUSING_PORTAL"
    When the support user gets the Ticket
    Then the HTTP status is 404
    And the error code is "TICKET_NOT_FOUND"
    And no sensitive Ticket fields are returned

  Scenario: Missing coarse read scope is forbidden
    Given employee "employee-123" is authenticated
    But the employee does not have a Ticket read scope
    When the employee gets the Ticket
    Then the HTTP status is 403
    And the error code is "FORBIDDEN"

  Scenario: Missing Ticket returns Not Found
    Given employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope
    And Ticket "018f0f1e-7b31-7a00-8f42-31f9b25b1a92" does not exist
    When the employee gets the missing Ticket
    Then the HTTP status is 404
    And the error code is "TICKET_NOT_FOUND"

  Scenario: Invalid Ticket ID is rejected
    Given employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope
    When the employee gets Ticket "not-a-uuid"
    Then the HTTP status is 400
    And the error code is "VALIDATION_ERROR"

  Scenario: Conditional GET returns Not Modified
    Given employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope
    And the Ticket requester is "employee-123"
    And the request contains If-None-Match "0"
    When the employee gets the Ticket
    Then the HTTP status is 304
    And the ETag is "0"
    And the response body is empty

  Scenario: Authorization runs before conditional response
    Given employee "employee-999" is authenticated
    And the employee has the "tickets:read:self" scope
    And the Ticket requester is "employee-123"
    And the request contains If-None-Match "0"
    When the employee gets the Ticket
    Then the HTTP status is 404
    And the service does not return 304
    And the service does not reveal the current version

  Scenario: Required sensitive-read Audit failure closes the read
    Given support user "support-100" is authenticated
    And the support user has the "tickets:read:queue" scope
    And the support user is authorized for the Ticket
    And the required sensitive-read Audit insert fails
    When the support user gets the Ticket
    Then the HTTP status is 500
    And the error code is "INTERNAL_ERROR"
    And no sensitive Ticket body is returned

  Scenario: Get Ticket does not mutate business state
    Given employee "employee-123" is authenticated
    And the employee has the "tickets:read:self" scope
    And the Ticket requester is "employee-123"
    And the original updatedAt and version are recorded
    When the employee gets the Ticket
    Then the Ticket updatedAt is unchanged
    And the Ticket version is unchanged
    And no Status History record is created
    And no Outbox record is created
