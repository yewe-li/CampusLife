INSERT INTO users(id,phone,nickname,role) VALUES
 (1,'13800000001','林同学','USER'),(2,'13800000002','陈同学','USER'),
 (101,'13900000001','东校区商户','MERCHANT'),(102,'13900000002','西校区商户','MERCHANT');
INSERT INTO shops(id,merchant_id,name,campus,category,average_price,status,description,address) VALUES
 (1,101,'课间咖啡','东校区','咖啡',1800,1,'图书馆旁的咖啡小店，适合自习间隙休息。','东校区大学路12号'),
 (2,102,'同窗小厨','西校区','餐饮',2500,1,'明码标价的家常简餐。','西校区学府街28号'),
 (3,101,'一碗热面','东校区','餐饮',1500,1,'下课后来一碗，学生友好的价格。','东校区文星巷6号'),
 (4,102,'周末球场','西校区','运动',5000,0,'维护中，暂不对外展示。','西校区体育路3号');
INSERT INTO vouchers(id,shop_id,title,discount_amount,min_spend,claim_start,claim_end,use_end,status) VALUES
 (1,1,'咖啡满20减5',500,2000,'2026-01-01','2036-01-01','2036-02-01',1),
 (2,2,'午餐满30减8',800,3000,'2026-01-01','2036-01-01','2036-02-01',1),
 (3,3,'已结束的开业活动',300,1500,'2020-01-01','2020-02-01','2020-03-01',1);
INSERT INTO voucher_stock(voucher_id,stock,initial_stock) VALUES(1,100,100),(2,50,50),(3,10,10);
