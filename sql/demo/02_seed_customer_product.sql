-- =====================================================================
-- 02_seed_customer_product.sql —— 演示客户（中文）与商品（中文）
--
-- 租户 1001 星途户外用品店：客户 2001-2006，商品 3001-3008
-- 租户 1002 智选数码生活馆：客户 2011-2016，商品 3101-3108
--
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE
-- 币种：CNY（人民币）；金额单位为元。
-- 客户 total_orders / total_spent 由 03_seed_orders.sql 末尾按真实订单汇总回填，
-- 此处先置 0，避免出现与订单不一致的历史消费金额。
-- 邮箱统一使用 example.com 测试域名；电话为合成测试值，非真实号码。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 租户 1001 星途户外用品店 —— 客户（6 位）
-- ---------------------------------------------------------------------
INSERT INTO customer
  (id, tenant_id, external_customer_id, email, phone, last_name, first_name, display_name,
   country_code, city, language_pref, currency_pref, total_orders, total_spent, avg_order_value,
   customer_tier, total_conversations, total_complaints, is_blacklisted, is_deleted, version)
VALUES
  (2001, 1001, 'cus_outdoor_001', 'zhangwei@example.com',  '+86-138-0000-0001', '张', '伟', '张伟', 'CN', '杭州市', 'zh', 'CNY', 0,0,0, 'VIP',     0,0,0,0,0),
  (2002, 1001, 'cus_outdoor_002', 'lina@example.com',      '+86-138-0000-0002', '李', '娜', '李娜', 'CN', '成都市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0),
  (2003, 1001, 'cus_outdoor_003', 'wangfang@example.com',  '+86-138-0000-0003', '王', '芳', '王芳', 'CN', '广州市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0),
  (2004, 1001, 'cus_outdoor_004', 'liuyang@example.com',   '+86-138-0000-0004', '刘', '洋', '刘洋', 'CN', '北京市', 'zh', 'CNY', 0,0,0, 'VIP',     0,0,0,0,0),
  (2005, 1001, 'cus_outdoor_005', 'chenjing@example.com',  '+86-138-0000-0005', '陈', '静', '陈静', 'CN', '深圳市', 'zh', 'CNY', 0,0,0, 'NEW',     0,0,0,0,0),
  (2006, 1001, 'cus_outdoor_006', 'zhaolei@example.com',   '+86-138-0000-0006', '赵', '磊', '赵磊', 'CN', '武汉市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0)
ON DUPLICATE KEY UPDATE
  email = VALUES(email), phone = VALUES(phone),
  last_name = VALUES(last_name), first_name = VALUES(first_name), display_name = VALUES(display_name),
  country_code = VALUES(country_code), city = VALUES(city),
  language_pref = VALUES(language_pref), currency_pref = VALUES(currency_pref),
  customer_tier = VALUES(customer_tier), is_deleted = VALUES(is_deleted);

-- ---------------------------------------------------------------------
-- 2. 租户 1002 智选数码生活馆 —— 客户（6 位）
-- ---------------------------------------------------------------------
INSERT INTO customer
  (id, tenant_id, external_customer_id, email, phone, last_name, first_name, display_name,
   country_code, city, language_pref, currency_pref, total_orders, total_spent, avg_order_value,
   customer_tier, total_conversations, total_complaints, is_blacklisted, is_deleted, version)
VALUES
  (2011, 1002, 'cus_digital_001', 'sunyue@example.com',    '+86-139-0000-0011', '孙', '悦', '孙悦', 'CN', '上海市', 'zh', 'CNY', 0,0,0, 'VIP',     0,0,0,0,0),
  (2012, 1002, 'cus_digital_002', 'zhoutao@example.com',   '+86-139-0000-0012', '周', '涛', '周涛', 'CN', '南京市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0),
  (2013, 1002, 'cus_digital_003', 'wumin@example.com',     '+86-139-0000-0013', '吴', '敏', '吴敏', 'CN', '苏州市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0),
  (2014, 1002, 'cus_digital_004', 'zhengkai@example.com',  '+86-139-0000-0014', '郑', '凯', '郑凯', 'CN', '杭州市', 'zh', 'CNY', 0,0,0, 'VIP',     0,0,0,0,0),
  (2015, 1002, 'cus_digital_005', 'fengxia@example.com',   '+86-139-0000-0015', '冯', '霞', '冯霞', 'CN', '西安市', 'zh', 'CNY', 0,0,0, 'NEW',     0,0,0,0,0),
  (2016, 1002, 'cus_digital_006', 'hejun@example.com',     '+86-139-0000-0016', '何', '军', '何军', 'CN', '重庆市', 'zh', 'CNY', 0,0,0, 'REGULAR', 0,0,0,0,0)
ON DUPLICATE KEY UPDATE
  email = VALUES(email), phone = VALUES(phone),
  last_name = VALUES(last_name), first_name = VALUES(first_name), display_name = VALUES(display_name),
  country_code = VALUES(country_code), city = VALUES(city),
  language_pref = VALUES(language_pref), currency_pref = VALUES(currency_pref),
  customer_tier = VALUES(customer_tier), is_deleted = VALUES(is_deleted);

-- ---------------------------------------------------------------------
-- 3. 租户 1001 星途户外用品店 —— 商品（8 个，覆盖 正常/低/无库存 + 上架/下架）
-- ---------------------------------------------------------------------
INSERT INTO product
  (id, tenant_id, external_product_id, handle, title, subtitle, description, description_plain,
   vendor, brand, product_type, category_l1, category_l2, tags, default_sku,
   currency, price, compare_at_price, cost_per_item, total_stock, stock_status,
   weight_grams, requires_shipping, is_taxable, language, rating_avg, rating_count, status, is_deleted, version)
VALUES
  (3001, 1001, 'prod_outdoor_001', 'waterproof-backpack-28l', '防水旅行背包 28L', '大容量防水徒步背包',
   '28L 大容量防水旅行背包，防泼水面料，多隔层收纳，适合徒步、通勤与短途旅行。', '28L 大容量防水旅行背包，防泼水面料，多隔层收纳，适合徒步与旅行。',
   '星途', '星途', '背包', '户外装备', '背包', '["热销","防水"]', 'XT-BAG-28L-001',
   'CNY', 329.0000, 399.0000, 150.0000, 120, 'in_stock', 900, 1, 1, 'zh', 4.70, 128, 1, 0, 0),
  (3002, 1001, 'prod_outdoor_002', 'lightweight-jacket', '轻量冲锋衣', '防风防泼水三合一',
   '轻量三合一冲锋衣，防风防泼水，透气内胆可拆卸，适合登山与日常通勤。', '轻量三合一冲锋衣，防风防泼水，透气内胆可拆卸。',
   '星途', '星途', '外套', '户外服装', '冲锋衣', '["防风","新品"]', 'XT-JKT-L-001',
   'CNY', 599.0000, 699.0000, 280.0000, 45, 'in_stock', 650, 1, 1, 'zh', 4.60, 86, 1, 0, 0),
  (3003, 1001, 'prod_outdoor_003', 'hiking-shoes', '轻便徒步鞋', '防滑耐磨透气',
   '轻便徒步鞋，防滑橡胶大底，透气网面，适合山地徒步与城市轻户外。', '轻便徒步鞋，防滑耐磨，透气网面。',
   '星途', '星途', '鞋靴', '户外鞋靴', '徒步鞋', '["防滑"]', 'XT-SHOE-42-001',
   'CNY', 469.0000, 529.0000, 210.0000, 8, 'low_stock', 800, 1, 1, 'zh', 4.50, 54, 1, 0, 0),
  (3004, 1001, 'prod_outdoor_004', 'steel-thermos', '不锈钢保温杯 500ml', '长效保温',
   '316 不锈钢保温杯 500ml，保温 12 小时，密封防漏，适合户外携带。', '316 不锈钢保温杯 500ml，保温 12 小时，密封防漏。',
   '星途', '星途', '水具', '户外水具', '保温杯', '["热销"]', 'XT-CUP-500-001',
   'CNY', 129.0000, 159.0000, 45.0000, 300, 'in_stock', 320, 1, 1, 'zh', 4.80, 210, 1, 0, 0),
  (3005, 1001, 'prod_outdoor_005', 'camping-lantern', '便携露营灯', '充电式三档调光',
   '充电式便携露营灯，三档调光，最长续航 30 小时，支持应急充电。', '充电式便携露营灯，三档调光，续航 30 小时。',
   '星途', '星途', '营地装备', '露营装备', '露营灯', '["露营"]', 'XT-LAMP-001',
   'CNY', 159.0000, 199.0000, 60.0000, 0, 'out_of_stock', 260, 1, 1, 'zh', 4.40, 39, 1, 0, 0),
  (3006, 1001, 'prod_outdoor_006', 'sun-hat', '户外遮阳帽', 'UPF50+ 防晒',
   'UPF50+ 防晒遮阳帽，可折叠收纳，透气速干，适合徒步与海滩。', 'UPF50+ 防晒遮阳帽，可折叠收纳，透气速干。',
   '星途', '星途', '配件', '户外配件', '遮阳帽', '["防晒"]', 'XT-HAT-001',
   'CNY', 79.0000, 99.0000, 25.0000, 150, 'in_stock', 120, 1, 1, 'zh', 4.30, 61, 1, 0, 0),
  (3007, 1001, 'prod_outdoor_007', 'travel-organizer', '旅行收纳包套装', '六件套分类收纳',
   '旅行收纳包六件套，衣物分类压缩收纳，防水耐磨，出行更整洁。', '旅行收纳包六件套，衣物分类压缩收纳，防水耐磨。',
   '星途', '星途', '收纳', '户外配件', '收纳包', '["收纳"]', 'XT-ORG-6P-001',
   'CNY', 89.0000, 119.0000, 30.0000, 60, 'in_stock', 240, 1, 1, 'zh', 4.20, 33, 1, 0, 0),
  (3008, 1001, 'prod_outdoor_008', 'sports-bottle', '运动水壶 750ml', 'Tritan 材质（已下架）',
   'Tritan 材质运动水壶 750ml，弹跳吸嘴（该款已下架，保留历史数据）。', 'Tritan 材质运动水壶 750ml（已下架）。',
   '星途', '星途', '水具', '户外水具', '水壶', '["下架"]', 'XT-BTL-750-001',
   'CNY', 99.0000, 129.0000, 35.0000, 200, 'in_stock', 180, 1, 1, 'zh', 4.10, 22, 2, 0, 0)
ON DUPLICATE KEY UPDATE
  title = VALUES(title), subtitle = VALUES(subtitle), description = VALUES(description),
  description_plain = VALUES(description_plain), brand = VALUES(brand), product_type = VALUES(product_type),
  category_l1 = VALUES(category_l1), category_l2 = VALUES(category_l2), tags = VALUES(tags),
  default_sku = VALUES(default_sku), currency = VALUES(currency), price = VALUES(price),
  compare_at_price = VALUES(compare_at_price), cost_per_item = VALUES(cost_per_item),
  total_stock = VALUES(total_stock), stock_status = VALUES(stock_status),
  language = VALUES(language), rating_avg = VALUES(rating_avg), rating_count = VALUES(rating_count),
  status = VALUES(status), is_deleted = VALUES(is_deleted);

-- ---------------------------------------------------------------------
-- 4. 租户 1002 智选数码生活馆 —— 商品（8 个，覆盖 正常/低/无库存 + 上架/下架）
-- ---------------------------------------------------------------------
INSERT INTO product
  (id, tenant_id, external_product_id, handle, title, subtitle, description, description_plain,
   vendor, brand, product_type, category_l1, category_l2, tags, default_sku,
   currency, price, compare_at_price, cost_per_item, total_stock, stock_status,
   weight_grams, requires_shipping, is_taxable, language, rating_avg, rating_count, status, is_deleted, version)
VALUES
  (3101, 1002, 'prod_digital_001', 'anc-bluetooth-earbuds', '主动降噪蓝牙耳机', 'ANC 主动降噪 40h 续航',
   '主动降噪蓝牙耳机，40 小时续航，蓝牙 5.3，低延迟，适合通勤与运动。', '主动降噪蓝牙耳机，40 小时续航，蓝牙 5.3。',
   '智选', '智选', '耳机', '数码配件', '蓝牙耳机', '["降噪","热销"]', 'ZX-EAR-ANC-001',
   'CNY', 499.0000, 599.0000, 220.0000, 200, 'in_stock', 60, 1, 1, 'zh', 4.70, 312, 1, 0, 0),
  (3102, 1002, 'prod_digital_002', 'mechanical-keyboard', '机械键盘 87 键', '热插拔轴体',
   '87 键机械键盘，热插拔轴体，RGB 背光，支持有线/无线双模。', '87 键机械键盘，热插拔轴体，RGB 背光。',
   '智选', '智选', '键盘', '数码配件', '机械键盘', '["热插拔"]', 'ZX-KB-87-001',
   'CNY', 399.0000, 459.0000, 180.0000, 90, 'in_stock', 900, 1, 1, 'zh', 4.60, 176, 1, 0, 0),
  (3103, 1002, 'prod_digital_003', 'wireless-mouse', '无线鼠标', '静音双模（低库存）',
   '静音无线鼠标，2.4G/蓝牙双模，续航持久，适合办公与学习。', '静音无线鼠标，2.4G/蓝牙双模。',
   '智选', '智选', '鼠标', '数码配件', '无线鼠标', '["静音"]', 'ZX-MS-WL-001',
   'CNY', 149.0000, 179.0000, 60.0000, 5, 'low_stock', 90, 1, 1, 'zh', 4.40, 98, 1, 0, 0),
  (3104, 1002, 'prod_digital_004', 'power-bank-20000', '移动电源 20000mAh', '22.5W 快充',
   '20000mAh 移动电源，22.5W 双向快充，双输出口，支持多协议。', '20000mAh 移动电源，22.5W 双向快充。',
   '智选', '智选', '充电', '数码配件', '移动电源', '["快充"]', 'ZX-PB-20000-001',
   'CNY', 199.0000, 249.0000, 90.0000, 260, 'in_stock', 420, 1, 1, 'zh', 4.50, 205, 1, 0, 0),
  (3105, 1002, 'prod_digital_005', 'gan-charger-65w', '65W 氮化镓充电器', '三口快充（暂时缺货）',
   '65W 氮化镓充电器，三口输出，支持笔记本与手机快充。', '65W 氮化镓充电器，三口输出。',
   '智选', '智选', '充电', '数码配件', '充电器', '["氮化镓"]', 'ZX-GAN-65W-001',
   'CNY', 129.0000, 169.0000, 55.0000, 0, 'out_of_stock', 150, 1, 1, 'zh', 4.50, 143, 1, 0, 0),
  (3106, 1002, 'prod_digital_006', 'smart-watch', '智能手表', '血氧/心率/多运动模式',
   '智能手表，支持血氧、心率监测与多种运动模式，续航 7 天。', '智能手表，支持血氧、心率监测，续航 7 天。',
   '智选', '智选', '穿戴', '智能硬件', '智能手表', '["热销"]', 'ZX-WATCH-001',
   'CNY', 899.0000, 999.0000, 420.0000, 40, 'in_stock', 120, 1, 1, 'zh', 4.60, 267, 1, 0, 0),
  (3107, 1002, 'prod_digital_007', 'portable-speaker', '便携蓝牙音箱', 'IPX7 防水',
   '便携蓝牙音箱，IPX7 防水，360° 环绕音效，续航 12 小时。', '便携蓝牙音箱，IPX7 防水，续航 12 小时。',
   '智选', '智选', '音箱', '数码配件', '蓝牙音箱', '["防水"]', 'ZX-SPK-001',
   'CNY', 259.0000, 299.0000, 110.0000, 130, 'in_stock', 380, 1, 1, 'zh', 4.40, 88, 1, 0, 0),
  (3108, 1002, 'prod_digital_008', 'usb-c-hub', 'USB-C 扩展坞 8 合 1', 'HDMI/读卡/网口（已下架）',
   'USB-C 八合一扩展坞，HDMI 4K、读卡器、千兆网口（该款已下架，保留历史数据）。', 'USB-C 八合一扩展坞，HDMI 4K、读卡器、千兆网口（已下架）。',
   '智选', '智选', '扩展', '数码配件', '扩展坞', '["下架"]', 'ZX-HUB-8IN1-001',
   'CNY', 229.0000, 279.0000, 95.0000, 70, 'in_stock', 160, 1, 1, 'zh', 4.20, 45, 2, 0, 0)
ON DUPLICATE KEY UPDATE
  title = VALUES(title), subtitle = VALUES(subtitle), description = VALUES(description),
  description_plain = VALUES(description_plain), brand = VALUES(brand), product_type = VALUES(product_type),
  category_l1 = VALUES(category_l1), category_l2 = VALUES(category_l2), tags = VALUES(tags),
  default_sku = VALUES(default_sku), currency = VALUES(currency), price = VALUES(price),
  compare_at_price = VALUES(compare_at_price), cost_per_item = VALUES(cost_per_item),
  total_stock = VALUES(total_stock), stock_status = VALUES(stock_status),
  language = VALUES(language), rating_avg = VALUES(rating_avg), rating_count = VALUES(rating_count),
  status = VALUES(status), is_deleted = VALUES(is_deleted);
