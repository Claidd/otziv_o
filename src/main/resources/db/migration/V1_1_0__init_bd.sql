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

create table IF NOT EXISTS  bots (
    id bigint not null auto_increment,
    login varchar(255) not null,
    password varchar(255) not null,
    fio varchar(255) not null,
    active bit,
    status varchar(255),
    counter integer,
    primary key (id)) engine=InnoDB;


create table IF NOT EXISTS  reviews (
    id bigint not null auto_increment,
    bot_id bigint,
    constraint bot_id foreign key (bot_id) references bots(id) ON delete CASCADE
    ON update CASCADE,
    primary key (id)) engine=InnoDB;

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

create TABLE IF NOT EXISTS text_promo (
    id int not null auto_increment,
    telephone_lead VARCHAR(2000) not null,
    primary key (id)) engine=InnoDB;

--        ALTER TABLE leads DROP INDEX UK_ljqpqpacyg8nhxc8mdvkijepl;


--insert into roles (name)
--values ('ROLE_ADMIN'), ('ROLE_CALLING'), ('ROLE_MANAGER'), ('ROLE_WORKER'), ('ROLE_USER'), ('ROLE_CLIENT');

--insert into users (username, password, fio, email, phone_number, activate_code, active, create_time)
--values ('alex', '$2a$10$IfEDIgjdQ0Ox634r2gBZF.k2ORbI7h85LyS4m7S3qm9aI5AWtGcSG', 'Хант Иван Игоревич', '2.12nps@mail.ru', '2.12nps@mail.ru', 'f9869242-3fd0-4464-a299-56f4e2c60e42', 1, '2023-07-26');

--INSERT INTO `text_promo`(`promo_text`) VALUES ('Здравствуйте, это тексты на проверку, проверьте, пожалуйста, в течении суток.
-- Все тексты будут нужны, и если в\n каких-то номерах что-то не подходит, напишите, пожалуйста, что исправить.\n Завтра будем публиковать, спасибо');

--INSERT INTO `text_promo`(`promo_text`) VALUES ('Здравствуйте, пишу вам по поводу отзывов, расскажу, как все происходит -\\n
--У нас есть подборка аккаунтов, которые мы ведём, как живых людей. То есть, ботами их назвать нельзя. Аккаунты \\n
--ведутся от 2017 года и позднее.\\n
--Далее либо вы пишите текст, и мы его публикуем, либо мы сами пишем текст, основываясь на вашей информации) \\n
--Публикуем обычно 1 отзыв в 2-3 дня. Но *не менее 10 в месяц* \\n
--1 отзыв 2 гис - _300 рублей.\\n
--✅Можете выслать вашу *ссылку* на 2гис, мы подготовим текст и вышлем на проверку
--Либо город, адрес и название фирмы)\\n
--Гарантия на каждый отзыв 3 месяца. \\n
--Публикуем *непрерывно* до того момента, когда вы попросите сделать паузу.\\n
--2 раза в месяц высылаем вам отчет и реквизиты для оплаты. Если нужен чек, сообщаете об этом заранее.'), ('Здравствуйте, это тексты на проверку, проверьте, пожалуйста, в течении суток. Все тексты будут нужны, и если в
--каких-то номерах что-то не подходит, напишите, пожалуйста, что исправить. \\n;
--Завтра будем публиковать, спасибо') )
--
--INSERT INTO `text_promo`(`promo_text`) VALUES ('Здравствуйте, это тексты на проверку, проверьте, пожалуйста, в течении суток. Все тексты будут нужны, и если в
--каких-то номерах что-то не подходит, напишите, пожалуйста, что исправить. \n;
--Завтра будем публиковать, спасибо');
--CHAR(13) + CHAR(10)
--
--INSERT INTO `text_promo`(`promo_text`) VALUES ('Здравствуйте, это тексты на проверку, проверьте, пожалуйста, в течении суток. Все тексты будут нужны, и если в
--каких-то номерах что-то не подходит, напишите, пожалуйста, что исправить.' + CHAR(13) + CHAR(10) + ';
--Завтра будем публиковать, спасибо');

--INSERT INTO `text_promo`(`promo_text`) VALUES ('Здравствуйте, пишу вам по поводу отзывов, расскажу, как все происходит -\\n
--У нас есть подборка аккаунтов, которые мы ведём, как живых людей. То есть, ботами их назвать нельзя. Аккаунты \\n
--ведутся от 2017 года и позднее.\\n
--Далее либо вы пишите текст, и мы его публикуем, либо мы сами пишем текст, основываясь на вашей информации) \\n
--Публикуем обычно 1 отзыв в 2-3 дня. Но *не менее 10 в месяц* \\n
--1 отзыв 2 гис - _300 рублей.\\n
--✅Можете выслать вашу *ссылку* на 2гис, мы подготовим текст и вышлем на проверку
--Либо город, адрес и название фирмы)\\n
--Гарантия на каждый отзыв 3 месяца. \\n
--Публикуем *непрерывно* до того момента, когда вы попросите сделать паузу.\\n
--2 раза в месяц высылаем вам отчет и реквизиты для оплаты. Если нужен чек, сообщаете об этом заранее.'), ('Здравствуйте, это тексты на проверку, проверьте, пожалуйста, в течении суток. Все тексты будут нужны, и если в
--каких-то номерах что-то не подходит, напишите, пожалуйста, что исправить. \\n;
--Завтра будем публиковать, спасибо') )


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

