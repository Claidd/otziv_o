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
    primary key (id)
)engine=InnoDB;

create table IF NOT EXISTS roles(
    id  int  auto_increment,
    name varchar(50) not null,
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

