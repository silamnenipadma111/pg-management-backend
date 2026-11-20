-- Upgrade an existing database created with an older 01_schema.sql:
-- UPI ID per PG, online payment order / details, and rent reminder tracking.
ALTER TABLE pgs ADD (upi_id VARCHAR2(100));
ALTER TABLE payments ADD (
    gateway_order_id VARCHAR2(40),
    payment_details  VARCHAR2(200),
    last_reminder_on DATE
);
