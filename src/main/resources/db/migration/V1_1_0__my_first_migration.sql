create table IF NOT EXISTS users (
    id bigint PRIMARY KEY,
    username varchar(30) not null unique,
    password varchar(80) not null,
    email varchar(50) not null unique,
    phone_number varchar(50) not null,
    activate_code varchar(255),
    active bit
--    primary key (id)
);

create table IF NOT EXISTS roles(
    id  int PRIMARY KEY,
    name varchar(50) not null
--    primary key (id)
);

create table IF NOT EXISTS users_roles(
    user_id bigint not null,
    role_id int not null,
    primary key(user_id, role_id),
    foreign key (user_id) references users(id),
    foreign key (role_id) references roles(id)
);

alter table users add constraint UK_6dotkott2kjsp8vw4d0m25fb7 unique (email);
alter table users_roles add constraint FKj6m8fwv7oqv74fcehir1a9ffy foreign key (role_id) references roles (id);
alter table users_roles add constraint FK2o0jvgh89lemvvo17cbqvdxaa foreign key (user_id) references users (id);


create sequence user_seq start with 1 increment by 1;
create sequence role_seq start with 1 increment by 1;

insert into roles (name)
values ('ROLE_ADMIN'), ('ROLE_CALLING'), ('ROLE_MANAGER'), ('ROLE_WORKER'), ('ROLE_USER'), ('ROLE_CLIENT');



--alter table user add constraint UK_ob8kqyqqgmefl0aco34akdtpe unique (email);
--insert into users(username, password, email) values ('user', '$2a$10$HlI5CfqsjHigrLa5CyzyD.57iAa5DyTt.oXx75wEcAxVf5Fxs1Z3m', 'ax@mail.ru');
--insert into users_roles (user_id, role_id) values (1,1), (2,2);