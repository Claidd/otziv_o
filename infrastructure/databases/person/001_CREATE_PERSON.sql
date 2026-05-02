DO $$
BEGIN
    IF NOT EXIST (SELECT FROM pg_database WHERE datname = "person") THEN
       CREATE DATABASE person;
END IF;
END $$;