-- V900 demo data already exists in local databases. Use a later version for safe upgrades.
-- Existing ISSUED rows are retained, with no redemption fields set.
ALTER TABLE voucher_orders
 DROP CHECK ck_order_status,
 ADD COLUMN redeemed_at DATETIME(6) NULL,
 ADD COLUMN redeemed_by BIGINT NULL,
 ADD CONSTRAINT fk_order_redeemer FOREIGN KEY (redeemed_by) REFERENCES users(id),
 ADD CONSTRAINT ck_order_redemption CHECK (
   (status = 'ISSUED' AND redeemed_at IS NULL AND redeemed_by IS NULL)
   OR (status = 'REDEEMED' AND redeemed_at IS NOT NULL AND redeemed_by IS NOT NULL
       AND redeemed_at < expires_at)
 );
