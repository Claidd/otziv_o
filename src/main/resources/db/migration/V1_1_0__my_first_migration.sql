create table user (
    active bit not null,
    id bigint not null auto_increment,
    activate_code varchar(255),
    email varchar(255) unique,
    password varchar(255),
    role varchar(20),
    username varchar(255)
    not null, primary key (id)) engine=InnoDB;

alter table user add constraint UK_ob8kqyqqgmefl0aco34akdtpe unique (email);
