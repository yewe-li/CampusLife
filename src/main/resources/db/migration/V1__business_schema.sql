CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 phone VARCHAR(11) NOT NULL,
 nickname VARCHAR(60) NOT NULL,
 role VARCHAR(16) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT uk_users_phone UNIQUE(phone),
 CONSTRAINT ck_users_role CHECK(role IN ('USER','MERCHANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE shops (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 merchant_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 campus VARCHAR(40) NOT NULL,
 category VARCHAR(40) NOT NULL,
 average_price INT NOT NULL,
 status INT NOT NULL DEFAULT 1,
 version INT NOT NULL DEFAULT 0,
 description VARCHAR(500) NOT NULL,
 address VARCHAR(200) NOT NULL,
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT fk_shop_merchant FOREIGN KEY(merchant_id) REFERENCES users(id),
 CONSTRAINT ck_shop_price CHECK(average_price >= 0),
 CONSTRAINT ck_shop_status CHECK(status IN(0,1)),
 INDEX idx_shops_filter(status,campus,category,average_price,id),
 INDEX idx_shops_merchant(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE vouchers (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 shop_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 discount_amount INT NOT NULL,
 min_spend INT NOT NULL,
 claim_start DATETIME(6) NOT NULL,
 claim_end DATETIME(6) NOT NULL,
 use_end DATETIME(6) NOT NULL,
 status INT NOT NULL DEFAULT 1,
 CONSTRAINT fk_voucher_shop FOREIGN KEY(shop_id) REFERENCES shops(id),
 CONSTRAINT ck_voucher_money CHECK(discount_amount>0 AND min_spend>=discount_amount),
 CONSTRAINT ck_voucher_dates CHECK(claim_start<claim_end AND claim_end<=use_end),
 CONSTRAINT ck_voucher_status CHECK(status IN(0,1)),
 INDEX idx_vouchers_shop_window(shop_id,status,claim_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE voucher_stock (
 voucher_id BIGINT PRIMARY KEY,
 stock INT NOT NULL,
 initial_stock INT NOT NULL,
 CONSTRAINT fk_stock_voucher FOREIGN KEY(voucher_id) REFERENCES vouchers(id),
 CONSTRAINT ck_stock_bounds CHECK(stock>=0 AND stock<=initial_stock)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE voucher_orders (
 id CHAR(32) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 voucher_id BIGINT NOT NULL,
 redeem_code CHAR(32) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT uk_order_user_voucher UNIQUE(user_id,voucher_id),
 CONSTRAINT uk_order_redeem_code UNIQUE(redeem_code),
 CONSTRAINT fk_order_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_order_voucher FOREIGN KEY(voucher_id) REFERENCES vouchers(id),
 CONSTRAINT ck_order_status CHECK(status='ISSUED'),
 INDEX idx_orders_user_created(user_id,created_at DESC,id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
