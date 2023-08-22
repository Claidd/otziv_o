create table IF NOT EXISTS users (
    id bigint auto_increment,
    username varchar(30) not null unique,
    password varchar(80) not null,
    fio varchar(50) not null,
    email varchar(50) not null unique,
    phone_number varchar(50) not null,
    activate_code varchar(255),
    active bit,
    create_time date,
    operator_id bigint,
    manager_id bigint,
    worker_id bigint,
--    operator_id bigint NULL,
--    manager_id bigint NULL,
--    worker_id bigint NULL,
--    INDEX `operator_id_idx` (`operator_id` ASC),
--    INDEX `manager_id_idx` (`manager_id` ASC),
--    INDEX `worker_id_idx` (`worker_id` ASC),
--    CONSTRAINT operator_fk FOREIGN KEY (operator_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE,
--    CONSTRAINT manager_fk FOREIGN KEY (manager_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE  CASCADE,
--    CONSTRAINT worker_fk FOREIGN KEY (worker_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE  CASCADE,
    primary key (id)
)engine=InnoDB;

create table IF NOT EXISTS roles(
    id  int  auto_increment,
    name varchar(50),
    primary key (id)
)engine=InnoDB;

create table IF NOT EXISTS users_roles(
    user_id bigint not null,
    role_id int not null,
    primary key(user_id, role_id),
    constraint user_id foreign key (user_id) references users(id) ON delete CASCADE
    ON update CASCADE,
    constraint role_id foreign key (role_id) references roles(id) ON delete CASCADE
    ON update CASCADE
)engine=InnoDB;

create table IF NOT EXISTS operators(
    operator_id  bigint  auto_increment,
--    manager_id bigint,
    user_id bigint unique,
--    CONSTRAINT operators_fk FOREIGN KEY (user_id) REFERENCES users (operator_id) ON UPDATE CASCADE ON DELETE CASCADE,
    primary key (operator_id)
)engine=InnoDB;

create table IF NOT EXISTS managers(
    manager_id  bigint  auto_increment,
    user_id bigint unique,
--    CONSTRAINT managers_fk FOREIGN KEY (user_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE,
    primary key (manager_id)
)engine=InnoDB;

create table IF NOT EXISTS workers(
    worker_id  bigint  auto_increment,
    user_id bigint unique,
--    CONSTRAINT workers_fk FOREIGN KEY (user_id) REFERENCES users (id) ON UPDATE CASCADE ON DELETE CASCADE,
    primary key (worker_id)
)engine=InnoDB;

CREATE TABLE IF NOT EXISTS operators_users (
  operator_id bigint not null,
  user_id bigint not null,
  INDEX user_id_idx (user_id ASC) ,
  INDEX operator_id_idx (operator_id ASC) ,
  CONSTRAINT user_id_fk
    FOREIGN KEY (user_id) REFERENCES users (id) ON delete CASCADE ON update CASCADE,
  CONSTRAINT operator_id_fk
    FOREIGN KEY (operator_id) REFERENCES operators (operator_id) ON delete CASCADE ON update CASCADE
--    UNIQUE INDEX operator_UNIQUE (operator_id ASC)
    )ENGINE = InnoDB;

    CREATE TABLE IF NOT EXISTS managers_users (
  manager_id bigint not null,
  user_id bigint not null,
  INDEX manager_user_id_idx (user_id ASC),
  INDEX manager_id_idx (manager_id ASC),
  CONSTRAINT manager_user_id_fk
    FOREIGN KEY (user_id) REFERENCES users (id) ON delete CASCADE ON update CASCADE,
  CONSTRAINT manager_id_fk
    FOREIGN KEY (manager_id) REFERENCES managers (manager_id) ON delete CASCADE ON update CASCADE
--    UNIQUE INDEX manager_UNIQUE (manager_id ASC)
    )ENGINE = InnoDB;

    CREATE TABLE IF NOT EXISTS workers_users (
  worker_id bigint not null,
  user_id bigint not null,
  INDEX worker_user_id_idx (user_id ASC) ,
  INDEX worker_id_idx (worker_id ASC) ,
  CONSTRAINT worker_user_id_fk
    FOREIGN KEY (user_id) REFERENCES users (id) ON delete CASCADE ON update CASCADE,
  CONSTRAINT worker_id_fk
    FOREIGN KEY (worker_id) REFERENCES workers (worker_id) ON delete CASCADE ON update CASCADE
--    UNIQUE INDEX worker_UNIQUE (worker_id ASC)
    )ENGINE = InnoDB;


create TABLE IF NOT EXISTS leads (
    id bigint not null auto_increment,
    telephone_lead VARCHAR(20) not null unique,
    city_lead VARCHAR(50) not null,
    comments_lead VARCHAR(500),
    lid_status VARCHAR(30),
    create_date DATE,
    update_status DATE,
    date_new_try DATE,
    operator bigint,
    CONSTRAINT fk_operator
        FOREIGN KEY (operator)
        REFERENCES users (id)
        ON delete CASCADE,
    manager bigint,
    CONSTRAINT fk_manager
        FOREIGN KEY (manager)
        REFERENCES users (id)
        ON delete CASCADE, primary key (id)) ENGINE=InnoDB;



create TABLE IF NOT EXISTS bots_status (
  bot_status_id BIGINT NOT NULL AUTO_INCREMENT,
  bot_status_title varchar(255) NULL,
  PRIMARY KEY (bot_status_id))
ENGINE = InnoDB;

create TABLE IF NOT EXISTS bots (
  bot_id BIGINT NOT NULL AUTO_INCREMENT,
  bot_login VARCHAR(45) NULL,
  bot_password VARCHAR(255) NULL,
  bot_fio VARCHAR(50) NULL,
  bot_counter INT NULL,
  bot_active BIT(1) NULL,
  bot_status BIGINT NULL,
  bot_worker BIGINT NULL,
  CONSTRAINT bot_status
    FOREIGN KEY (bot_status)
    REFERENCES bots_status (bot_status_id)
    ON delete NO ACTION
    ON update CASCADE,
    CONSTRAINT bot_worker
    FOREIGN KEY (bot_worker)
    REFERENCES users (id)
    ON delete NO ACTION
    ON update CASCADE,
    PRIMARY KEY (bot_id))
ENGINE = InnoDB;

create TABLE IF NOT EXISTS text_promo (
    id bigint auto_increment,
    promo_text varchar(2555),
    primary key (id)) engine=InnoDB;


CREATE TABLE IF NOT EXISTS categorys (
    category_id bigint NOT NULL AUTO_INCREMENT,
    category_title varchar(255),
--    subcategory_title bigint NULL,
    PRIMARY KEY (category_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS subcategoryes (
    subcategory_id bigint NOT NULL AUTO_INCREMENT,
    category_id bigint,
    subcategory_title varchar(255),
    CONSTRAINT category_id_fk FOREIGN KEY (category_id) REFERENCES categorys (category_id) ON UPDATE CASCADE ON DELETE CASCADE,
    PRIMARY KEY (subcategory_id)
) ENGINE=InnoDB;


CREATE TABLE IF NOT EXISTS company_status (
    company_status_id bigint NOT NULL AUTO_INCREMENT,
    status_title varchar(255),
    PRIMARY KEY (company_status_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS filial (
  filial_id bigint NOT NULL AUTO_INCREMENT,
  filial_title VARCHAR(45) NULL,
  filial_url VARCHAR(1000) NULL,
  company_id bigint,
  PRIMARY KEY (filial_id))
ENGINE = InnoDB;

    CREATE TABLE IF NOT EXISTS companies (
  company_id bigint NOT NULL AUTO_INCREMENT,
  company_phone VARCHAR(12) NULL,
  company_city VARCHAR(45) NULL,
  company_title VARCHAR(45) NOT NULL,
  company_email VARCHAR(45) NULL,
  company_status bigint NULL,
  company_category bigint NULL,
  company_subcategory bigint NULL,
  company_filial bigint NULL,
  company_comments VARCHAR(2000) NULL,
  company_counter_no_pay INT NULL DEFAULT 0,
  company_counter_pay INT NULL DEFAULT 0,
  company_orders VARCHAR(45) NULL,
  company_active BIT(1) NULL,
  company_sum INT NULL DEFAULT 0,
  create_date DATE NULL,
  update_status DATE NULL,
  date_new_try DATE NULL,
  PRIMARY KEY (company_id),
  INDEX company_category_idx (company_category ASC),
  INDEX company_subcategory_idx (company_subcategory ASC),
  INDEX company_status_idx (company_status ASC),
  INDEX company_filial_idx (company_filial ASC),
  CONSTRAINT company_category
    FOREIGN KEY (company_category)
    REFERENCES categorys (category_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  CONSTRAINT company_subcategory
    FOREIGN KEY (company_subcategory)
    REFERENCES subcategoryes (subcategory_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  CONSTRAINT company_status
    FOREIGN KEY (company_status)
    REFERENCES company_status (company_status_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE,
  CONSTRAINT company_filial
    FOREIGN KEY (company_filial)
    REFERENCES filial (filial_id)
    ON DELETE CASCADE
    ON UPDATE CASCADE)
ENGINE = InnoDB;


--create table IF NOT EXISTS operators(
--    operator_id  bigint  auto_increment,
--    primary key (operator_id)
--)engine=InnoDB;
--
--create table IF NOT EXISTS managers(
--    manager_id  bigint  auto_increment,
--    primary key (manager_id)
--)engine=InnoDB;

--create table IF NOT EXISTS workers(
--    worker_id  bigint  auto_increment,
--    primary key (worker_id)
--)engine=InnoDB;
--
---- Таблица otziv.operators
--CREATE TABLE IF NOT EXISTS user_operators (
--  operator_id bigint NOT NULL AUTO_INCREMENT,
--  user_id bigint NULL,
--  PRIMARY KEY (operator_id),
--  CONSTRAINT fk_operator_user
--    FOREIGN KEY (user_id)
--    REFERENCES users (id)
--    ON DELETE SET NULL
--)engine=InnoDB;
--
---- Таблица otziv.managers
--CREATE TABLE IF NOT EXISTS user_managers (
--  manager_id bigint NOT NULL AUTO_INCREMENT,
--  user_id bigint NULL,
--  PRIMARY KEY (manager_id),
--  CONSTRAINT fk_manager_user
--    FOREIGN KEY (user_id)
--    REFERENCES users (id)
--    ON DELETE SET NULL
--)engine=InnoDB;
--
--CREATE TABLE IF NOT EXISTS user_workers (
--  worker_id bigint NOT NULL AUTO_INCREMENT,
--  user_id bigint NULL,
--  PRIMARY KEY (worker_id),
--  CONSTRAINT fk_worker_user
--    FOREIGN KEY (user_id)
--    REFERENCES users (id)
--    ON DELETE SET NULL
--)engine=InnoDB;




--create table IF NOT EXISTS  reviews (
----    id bigint not null auto_increment,
----    bot_id bigint,
----    constraint bot_id foreign key (bot_id) references bots(bot_id) ON delete CASCADE
----    ON update CASCADE,
----    primary key (id)) engine=InnoDB;

--        ALTER TABLE leads DROP INDEX UK_ljqpqpacyg8nhxc8mdvkijepl;








--insert into bots (login, password, fio, active, status, counter)
--values ('9086431055','pass','Петров Н.Г.',true,'в работе',0);



--create table IF NOT EXISTS bots (
--    id bigint not null auto_increment,
--    login varchar(45) not null,
--    password varchar(255) not null,
--    fio varchar(50) not null,
--    counter int,
--    active bit,
--    status varchar(30),
--    primary key (id)) engine=InnoDB;
--
--create table IF NOT EXISTS reviews (
--    id bigint not null auto_increment,
--    bot_id bigint not null,
--    constraint bot_id FOREIGN KEY (bot_id) REFERENCES bots (id),
--    primary key (id)) engine=InnoDB;
--

--
--
--create table IF NOT EXISTS bots_review_list (
--    bot_id bigint not null,
--    review_list_id bigint not null) engine=InnoDB;
--
--
--create table IF NOT EXISTS leads (
--    user_id bigint not null,
--    city varchar(255),
--    telephone_lead varchar(255),
--    primary key (user_id)) engine=InnoDB;
--
--



--alter table users add constraint UK_6dotkott2kjsp8vw4d0m25fb7 unique (email);
--alter table users_roles add constraint FKj6m8fwv7oqv74fcehir1a9ffy foreign key (role_id) references roles (id);
--alter table users_roles add constraint FK2o0jvgh89lemvvo17cbqvdxaa foreign key (user_id) references users (id);
--
--alter table bots_review_list add constraint UK_b6ls19knv592994a365poiw3 unique (review_list_id)
--alter table bots_review_list add constraint FKg5le58rqqx06v3qxq9pcc1iyo foreign key (review_list_id) references reviews (id)
--alter table bots_review_list add constraint FK8i2eqfou3y223kedlan1a97ss foreign key (bot_id) references bots (id)
--alter table leads add constraint FK10u8b7klywjncgkn7xffx7ncu foreign key (user_id) references users (id)
--
--
--

--
--
----insert into users(username, password, email) values ('user', '$2a$10$HlI5CfqsjHigrLa5CyzyD.57iAa5DyTt.oXx75wEcAxVf5Fxs1Z3m', 'ax@mail.ru');
----insert into users_roles (user_id, role_id) values (1,1), (2,2);
--
----create sequence user_seq start with 1 increment by 1;
----create sequence role_seq start with 1 increment by 1;


--таблица многие ко многим между полтзователем и ролью

