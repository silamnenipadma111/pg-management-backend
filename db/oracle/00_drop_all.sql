-- Drops all PG Management tables (use with care - deletes all data).
BEGIN
    FOR t IN (SELECT table_name FROM user_tables WHERE table_name IN (
        'PAYMENTS', 'BOOKINGS', 'BEDS', 'ROOMS', 'PG_MEDIA', 'PGS', 'NOTIFICATIONS', 'SUPPORT_REQUESTS', 'APP_USERS'))
    LOOP
        EXECUTE IMMEDIATE 'DROP TABLE ' || t.table_name || ' CASCADE CONSTRAINTS PURGE';
    END LOOP;
END;
/
