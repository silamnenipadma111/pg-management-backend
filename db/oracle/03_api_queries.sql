-- PG Management - reference SQL for the main API operations (Oracle).
-- The application issues equivalent statements through Spring Data JPA; these are handy for
-- reporting, debugging and for anyone building a different client on top of the same schema.
-- Bind variables (:name) map to the API request parameters.

-- POST /api/auth/signin : look up a user by email + role
SELECT id, name, email, phone, password_hash, role FROM app_users
WHERE LOWER(email) = LOWER(:email) AND role = :role;

-- GET /api/pgs : nearby PGs (Haversine distance in km) with filters, sorted by distance
SELECT * FROM (
    SELECT p.id, p.name, p.area, p.city, p.gender_preference,
           6371 * 2 * ATAN2(SQRT(a), SQRT(1 - a)) AS distance_km,
           (SELECT MIN(r.monthly_rent) FROM rooms r WHERE r.pg_id = p.id) AS min_rent,
           (SELECT COUNT(*) FROM beds b JOIN rooms r ON r.id = b.room_id
             WHERE r.pg_id = p.id AND b.status = 'AVAILABLE') AS available_beds
    FROM (
        SELECT p.*,
               POWER(SIN((p.latitude - :lat) * ACOS(-1) / 360), 2)
             + COS(:lat * ACOS(-1) / 180) * COS(p.latitude * ACOS(-1) / 180)
             * POWER(SIN((p.longitude - :lng) * ACOS(-1) / 360), 2) AS a
        FROM pgs p
        WHERE p.active = 1
          AND (:city IS NULL OR LOWER(p.city) = LOWER(:city))
          AND (:gender IS NULL OR p.gender_preference IN (:gender, 'ANY'))
    ) p
    WHERE EXISTS (SELECT 1 FROM rooms r WHERE r.pg_id = p.id
                    AND (:sharing IS NULL OR r.sharing_type = :sharing)
                    AND r.monthly_rent BETWEEN NVL(:min_rent, 0) AND NVL(:max_rent, 1e9))
)
WHERE :radius_km IS NULL OR distance_km <= :radius_km
ORDER BY distance_km;

-- GET /api/pgs/{id} : floors -> rooms -> beds for the booking page
SELECT r.floor_number, r.room_number, r.sharing_type, r.ac, r.attached_bathroom,
       r.daily_rent, r.weekly_rent, r.monthly_rent, b.id AS bed_id, b.bed_label, b.status
FROM rooms r JOIN beds b ON b.room_id = r.id
WHERE r.pg_id = :pg_id
ORDER BY r.floor_number, r.room_number, b.bed_label;

-- POST /api/bookings : block the bed (optimistic lock on version) and create the request
UPDATE beds SET status = 'BLOCKED', version = version + 1
WHERE id = :bed_id AND status = 'AVAILABLE' AND version = :version;
INSERT INTO bookings (customer_id, pg_id, bed_id, booking_type, payment_plan, duration,
                      start_date, end_date, total_amount, status, customer_note)
VALUES (:customer_id, :pg_id, :bed_id, :booking_type, :payment_plan, :duration,
        :start_date, :end_date, :total_amount, 'REQUESTED', :note);

-- GET /api/owner/dashboard/approvals : pending booking requests with customer contact details
SELECT bk.id, p.name AS pg_name, r.room_number, b.bed_label, bk.booking_type, bk.start_date,
       bk.total_amount, u.name AS customer_name, u.phone, u.email, bk.created_at
FROM bookings bk
JOIN pgs p ON p.id = bk.pg_id
JOIN beds b ON b.id = bk.bed_id
JOIN rooms r ON r.id = b.room_id
JOIN app_users u ON u.id = bk.customer_id
WHERE p.owner_id = :owner_id AND bk.status = 'REQUESTED'
ORDER BY bk.created_at;

-- POST /api/owner/bookings/{id}/reject (or customer cancel) : release the bed
UPDATE bookings SET status = 'REJECTED', owner_note = :note, updated_at = SYSTIMESTAMP WHERE id = :booking_id;
UPDATE beds SET status = 'AVAILABLE', version = version + 1
WHERE id = (SELECT bed_id FROM bookings WHERE id = :booking_id);

-- POST /api/owner/payments/{id}/confirm : mark paid, issue invoice, bed becomes BOOKED
UPDATE payments SET status = 'PAID', paid_at = SYSTIMESTAMP,
       invoice_number = 'INV-' || TO_CHAR(SYSDATE, 'YYYY') || '-' || LPAD(id, 6, '0')
WHERE id = :payment_id AND status = 'SUBMITTED';
UPDATE bookings SET status = 'BOOKED', updated_at = SYSTIMESTAMP
WHERE id = (SELECT booking_id FROM payments WHERE id = :payment_id) AND status = 'APPROVED';
UPDATE beds SET status = 'BOOKED', version = version + 1
WHERE id = (SELECT bed_id FROM bookings WHERE id = (SELECT booking_id FROM payments WHERE id = :payment_id));

-- GET /api/owner/dashboard/availability : bed availability per PG
SELECT p.id, p.name,
       COUNT(b.id) AS total_beds,
       SUM(CASE WHEN b.status = 'AVAILABLE' THEN 1 ELSE 0 END) AS available,
       SUM(CASE WHEN b.status = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked,
       SUM(CASE WHEN b.status = 'BOOKED' THEN 1 ELSE 0 END) AS booked,
       SUM(CASE WHEN b.status = 'UNAVAILABLE' THEN 1 ELSE 0 END) AS unavailable
FROM pgs p JOIN rooms r ON r.pg_id = p.id JOIN beds b ON b.room_id = r.id
WHERE p.owner_id = :owner_id
GROUP BY p.id, p.name
ORDER BY p.name;

-- GET /api/owner/dashboard/rents : pending / overdue rents per owner
SELECT p.name AS pg_name, u.name AS customer_name, u.phone, r.room_number, b.bed_label,
       pay.installment_no, pay.amount, pay.due_date, pay.status,
       CASE WHEN pay.due_date < TRUNC(SYSDATE) THEN 'Y' ELSE 'N' END AS overdue
FROM payments pay
JOIN bookings bk ON bk.id = pay.booking_id
JOIN pgs p ON p.id = bk.pg_id
JOIN app_users u ON u.id = bk.customer_id
JOIN beds b ON b.id = bk.bed_id
JOIN rooms r ON r.id = b.room_id
WHERE p.owner_id = :owner_id AND pay.status IN ('DUE', 'SUBMITTED')
  AND bk.status IN ('APPROVED', 'BOOKED')
ORDER BY pay.due_date;

-- GET /api/payments/my?scope=upcoming|history : customer rent tabs
SELECT pay.*, p.name AS pg_name
FROM payments pay JOIN bookings bk ON bk.id = pay.booking_id JOIN pgs p ON p.id = bk.pg_id
WHERE bk.customer_id = :customer_id
  AND ((:scope = 'upcoming' AND pay.status IN ('DUE', 'SUBMITTED')) OR (:scope = 'history' AND pay.status = 'PAID'))
ORDER BY CASE WHEN :scope = 'history' THEN pay.paid_at END DESC, pay.due_date, pay.installment_no;
