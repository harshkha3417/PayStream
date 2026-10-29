-- Increase sequence increment to 50 to enable Hibernate JDBC batching for payment_status_history
ALTER SEQUENCE payment_status_history_id_seq INCREMENT BY 50;
