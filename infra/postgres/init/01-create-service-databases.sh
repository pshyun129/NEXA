#!/usr/bin/env bash
set -euo pipefail

# 비밀번호가 하나라도 빠졌으면 작업 시작 전에 중단합니다.
: "${USER_DB_PASSWORD:?USER_DB_PASSWORD is required}"
: "${LEAVE_DB_PASSWORD:?LEAVE_DB_PASSWORD is required}"
: "${APPROVAL_DB_PASSWORD:?APPROVAL_DB_PASSWORD is required}"
: "${ATTENDANCE_DB_PASSWORD:?ATTENDANCE_DB_PASSWORD is required}"
: "${PAYROLL_DB_PASSWORD:?PAYROLL_DB_PASSWORD is required}"
: "${MIGRATION_DB_PASSWORD:?MIGRATION_DB_PASSWORD is required}"
: "${AI_DB_PASSWORD:?AI_DB_PASSWORD is required}"

create_service_database() {
    local db_name="$1"
    local db_user="$2"
    local db_password="$3"

    psql \
        --username "$POSTGRES_USER" \
        --dbname postgres \
        --set=ON_ERROR_STOP=1 \
        --set=db_name="$db_name" \
        --set=db_user="$db_user" \
        --set=db_password="$db_password" <<'SQL'
SELECT format(
    'CREATE ROLE %I LOGIN PASSWORD %L',
    :'db_user',
    :'db_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = :'db_user'
)
\gexec

SELECT format(
    'CREATE DATABASE %I OWNER %I',
    :'db_name',
    :'db_user'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = :'db_name'
)
\gexec

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO :"db_user";
SQL

    psql \
        --username "$POSTGRES_USER" \
        --dbname "$db_name" \
        --set=ON_ERROR_STOP=1 \
        --set=db_user="$db_user" <<'SQL'
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"db_user";
SQL
}

create_service_database nexa_user nexa_user_app "$USER_DB_PASSWORD"
create_service_database nexa_leave nexa_leave_app "$LEAVE_DB_PASSWORD"
create_service_database nexa_approval nexa_approval_app "$APPROVAL_DB_PASSWORD"
create_service_database nexa_attendance nexa_attendance_app "$ATTENDANCE_DB_PASSWORD"
create_service_database nexa_payroll nexa_payroll_app "$PAYROLL_DB_PASSWORD"
create_service_database nexa_migration nexa_migration_app "$MIGRATION_DB_PASSWORD"
create_service_database nexa_ai nexa_ai_app "$AI_DB_PASSWORD"