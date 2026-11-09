"""명시 opt-in 시에만 네트워크가 차단된 새 PostgreSQL 17 컨테이너를 사용한다."""
import concurrent.futures
import os
import subprocess
import time
import unittest
import uuid
from pathlib import Path


@unittest.skipUnless(os.environ.get("E2E_SQL_TESTS") == "1", "E2E_SQL_TESTS=1로 격리 Docker SQL 검증 활성화")
class PostgresFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[1]
        cls.container = "leave-fixture-test-" + uuid.uuid4().hex[:12]
        cls.docker("run", "--rm", "-d", "--network", "none", "--name", cls.container,
                   "-e", "POSTGRES_PASSWORD=isolated-test-only", "-e", "POSTGRES_DB=leave_fixture_test",
                   os.environ.get("E2E_POSTGRES_IMAGE", "postgres:17"))
        cls.addClassCleanup(lambda: cls.docker("stop", cls.container))
        for _ in range(60):
            ready = cls.docker("exec", cls.container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "leave_fixture_test", require=False)
            if ready.returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("격리 PostgreSQL 준비 실패")
        migrations = cls.root.parents[1] / "src/main/resources/db/migration"
        for path in sorted(migrations.glob("V*.sql")):
            cls.sql(path.read_text(encoding="utf-8"), database="leave_fixture_test")

    @classmethod
    def docker(cls, *args, require=True, input=None):
        result = subprocess.run(["docker", *args], input=input, capture_output=True, text=True, encoding="utf-8", timeout=90)
        if require and result.returncode:
            raise AssertionError("격리 Docker/psql 실패: " + result.stderr[-1600:])
        return result

    @classmethod
    def sql(cls, text, database=None, variables=None, require=True):
        command = ["exec", "-i", cls.container, "psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-U", "postgres", "-d", database or cls.database]
        for name, value in (variables or {}).items():
            command.extend(("-v", f"{name}={value}"))
        return cls.docker(*command, input=text, require=require)

    def setUp(self):
        self.database = "leave_fixture_" + uuid.uuid4().hex[:10]
        type(self).database = self.database
        self.sql(f"CREATE DATABASE {self.database} TEMPLATE leave_fixture_test;", database="postgres")
        self.variables = {"expected_database": self.database, "fixture_key": "SQL_TEST", "allow_dev_write": "YES",
                          "dedicated_employee_confirmed": "YES", "employee_id": "103", "minutes": "7200",
                          "valid_from": "2026-01-01T00:00:00Z", "expires_on": "2099-01-01T00:00:00Z",
                          "credit_at": "2026-01-01T00:00:00Z"}

    def file(self, name, require=True, overrides=None):
        return self.sql((self.root / "init" / name).read_text(encoding="utf-8"),
                        variables={**self.variables, **(overrides or {})}, require=require)

    def prepare(self):
        self.file("01_balance_type.sql")
        balance_id = self.sql("SELECT balance_type_id FROM leave_balance_type;").stdout.strip()
        policy_id = self.sql("INSERT INTO leave_policy_group(name) VALUES('E2E_SQL_TEST_POLICY') RETURNING policy_id;").stdout.splitlines()[0]
        version_id = self.sql(f"INSERT INTO leave_policy_version(policy_id,version_no,base_type) VALUES({policy_id},1,'HIRE_DATE') RETURNING policy_version_id;").stdout.splitlines()[0]
        self.sql(f"UPDATE leave_policy_group SET active_policy_version_id={version_id} WHERE policy_id={policy_id}; INSERT INTO employee_leave_policy_assignment(emp_id,policy_id,effective_from) VALUES(103,{policy_id},'2026-01-01');")
        self.variables.update(balance_type_id=balance_id, policy_id=policy_id, version_id=version_id)

    def totals(self):
        return self.sql("SELECT count(*),sum(quantity_delta),sum(reserved_delta) FROM leave_ledger;").stdout.strip()

    def test_schema_v1_to_v6_and_postgres17(self):
        self.assertTrue(self.sql("SHOW server_version;").stdout.startswith("17."))
        self.assertEqual("13", self.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public';").stdout.strip())
        self.assertEqual("YES", self.sql("SELECT is_nullable FROM information_schema.columns WHERE table_name='leave_policy_version' AND column_name='effective_from';").stdout.strip())

    def test_balance_type_repeat_requires_manifest_and_exact_properties(self):
        self.file("01_balance_type.sql")
        identifier = self.sql("SELECT balance_type_id FROM leave_balance_type;").stdout.strip()
        self.assertNotEqual(0, self.file("01_balance_type.sql", require=False).returncode)
        self.file("01_balance_type.sql", overrides={"expected_balance_type_id": identifier})
        self.sql("UPDATE leave_balance_type SET is_active=false;")
        self.assertNotEqual(0, self.file("01_balance_type.sql", require=False, overrides={"expected_balance_type_id": identifier}).returncode)

    def test_wrong_database_aborts_before_insert(self):
        self.assertNotEqual(0, self.file("01_balance_type.sql", require=False, overrides={"expected_database": "wrong_dev"}).returncode)
        self.assertEqual("0", self.sql("SELECT count(*) FROM leave_balance_type;").stdout.strip())

    def test_first_credit_and_replay_no_duplicate(self):
        self.prepare()
        self.file("02_initial_grant.sql")
        self.file("02_initial_grant.sql")
        self.assertEqual("1|7200|0", self.totals())
        self.assertEqual("1", self.sql("SELECT count(*) FROM leave_grant;").stdout.strip())

    def test_used_credit_replay_preserves_current_balance(self):
        self.prepare()
        self.file("02_initial_grant.sql")
        self.sql("INSERT INTO leave_ledger(account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key) SELECT account_id,grant_id,'ADJUST',-60,0,'used-adjust' FROM leave_grant;")
        self.file("02_initial_grant.sql")
        self.assertEqual("2|7140|0", self.totals())

    def test_mismatched_credit_amount_rejected(self):
        self.prepare()
        self.file("02_initial_grant.sql")
        self.assertNotEqual(0, self.file("02_initial_grant.sql", require=False, overrides={"minutes": "7300"}).returncode)
        self.assertEqual("1|7200|0", self.totals())

    def test_run_named_assigned_policy_requires_exact_expected_name(self):
        self.prepare()
        policy_name = 'E2E_SQL_TEST_RUN_ABC_POLICY_CHANGED'
        self.sql(f"UPDATE leave_policy_group SET name='{policy_name}';")
        self.file("02_initial_grant.sql", overrides={"expected_policy_name": policy_name})
        self.file("02_initial_grant.sql", overrides={"expected_policy_name": policy_name})
        self.assertEqual("1|7200|0", self.totals())
        self.assertNotEqual(0, self.file("02_initial_grant.sql", require=False, overrides={"expected_policy_name": "E2E_SQL_TEST_OTHER"}).returncode)

    def test_ledger_failure_rolls_back_account_and_grant(self):
        self.prepare()
        self.sql("CREATE FUNCTION reject_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated failure'; END $$; CREATE TRIGGER fail_fixture BEFORE INSERT ON leave_ledger FOR EACH ROW EXECUTE FUNCTION reject_fixture();")
        self.assertNotEqual(0, self.file("02_initial_grant.sql", require=False).returncode)
        self.assertEqual("0|0|0", self.sql("SELECT (SELECT count(*) FROM leave_account),(SELECT count(*) FROM leave_grant),(SELECT count(*) FROM leave_ledger);").stdout.strip())

    def test_reversed_credit_not_accepted(self):
        self.prepare()
        self.file("02_initial_grant.sql")
        self.sql("INSERT INTO leave_ledger(account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key,reverses_entry_id) SELECT account_id,grant_id,'REVOKE',-7200,0,'reverse-credit',entry_id FROM leave_ledger;")
        self.assertNotEqual(0, self.file("02_initial_grant.sql", require=False).returncode)
        self.assertEqual("2|0|0", self.totals())

    def test_invalid_reservation_aborts_without_repair(self):
        self.prepare()
        self.file("02_initial_grant.sql")
        self.sql("INSERT INTO leave_ledger(account_id,grant_id,event_type,quantity_delta,reserved_delta,idempotency_key) SELECT account_id,grant_id,'RESERVE',0,7300,'bad-reserve' FROM leave_grant;")
        self.assertNotEqual(0, self.file("02_initial_grant.sql", require=False).returncode)
        self.assertEqual("2|7200|7300", self.totals())

    def test_concurrent_initial_credit_serializes(self):
        self.prepare()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda _: self.file("02_initial_grant.sql"), range(2)))
        self.assertTrue(all(r.returncode == 0 for r in results))
        self.assertEqual("1|7200|0", self.totals())


if __name__ == "__main__":
    unittest.main()
