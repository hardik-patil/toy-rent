#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE USER toyuser WITH PASSWORD 'toypass';
    CREATE DATABASE toydb OWNER toyuser;

    CREATE USER bookinguser WITH PASSWORD 'bookingpass';
    CREATE DATABASE bookingdb OWNER bookinguser;
EOSQL
