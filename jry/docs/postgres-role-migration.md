# PostgreSQL least-privilege role migration gate

Current status: blocked on external migration evidence. The checked-in Compose stack still gives the backend, Metabase metadata store, and Metabase analytics connection the same PostgreSQL login. This document defines the target and the release gate; it is not evidence that the existing `postgres-data` volume has been migrated.

## Target role boundary

Use four independently rotated login roles. The deployment may choose different concrete names, but each credential must have only one purpose.

| Role | Allowed scope | Must not have |
| --- | --- | --- |
| database owner | Own `fulfillment_hub`, its application schemas, and migration-created objects; used only by an operator-controlled, one-shot schema migration job | Backend runtime, Metabase runtime, routine login, or analytics access |
| application runtime | Connect to `fulfillment_hub`; use the application schemas and sequences; perform only the DML required by the backend | Database/schema ownership, role administration, DDL, `BYPASSRLS`, or access to the `metabase` database |
| Metabase metadata | Connect only to the `metabase` database and own/use only Metabase's metadata objects | Access to `fulfillment_hub`, application tables, or analytics views |
| analytics read-only | Connect to `fulfillment_hub`; `USAGE` on `analytics`; `SELECT` on the approved analytics views, including appropriate future-view default privileges | Writes, application-schema reads, object ownership, role administration, or access to the `metabase` database |

The Metabase service uses the Metabase metadata login. Its configured "Fulfillment Analytics" data source uses the analytics read-only login. The backend uses the application runtime login. Owner credentials are never supplied to those long-running services.

Schema changes run before the backend starts, as a separately authorized one-shot job using the database owner credential. They must not be implemented as a Flyway application migration: an application migration cannot safely create or rotate cluster roles/databases, and giving the runtime deployment role-administration privileges would defeat the boundary.

## Existing-volume migration gate

All gates below are external operations. Keep Ticket 09 `in-progress` until the recorded evidence exists for the actual target volume.

1. **Authorize and identify.** Record the target host, Compose project, exact `postgres-data` volume identity, maintenance window, operator, and rollback owner. Do not touch similarly named volumes.
2. **Back up and prove recovery.** Take a consistent database backup plus the deployment configuration needed to recover it. Perform a restore drill into an isolated PostgreSQL instance and record checksums, row/object counts, and the restore result. A backup file without a restore drill does not pass this gate.
3. **Inventory before change.** Capture PostgreSQL version, databases, login/superuser flags, memberships, database/schema/table/sequence owners, explicit grants, default privileges, extensions, Flyway history, and active sessions. Confirm which current shared login owns each object.
4. **Prepare secrets privately.** Generate four distinct strong passwords in an approved secret store or owner-only `0600` files. Do not place values in source, command arguments, shell history, logs, or a committed `.env`.
5. **Review an idempotent operator script.** The script must create/alter the four roles, transfer ownership to the database owner, grant the minimum runtime privileges, configure future-object default privileges, and revoke inherited/public access. Database creation and role changes belong to an operator phase; schema grants belong to a database-scoped phase. Each phase must fail closed and print no secrets.
6. **Migrate without dropping the old login.** Stop writers, run the reviewed phases with an authorized administrative connection, and re-run the inventory. Preserve the old role for rollback but disable its login after consumers have switched; do not drop it during the same change window.
7. **Switch one consumer at a time.** Run the owner-only schema migration, then start the backend with the application runtime credential. Start Metabase with the Metabase metadata credential and configure its analytics connection with the analytics read-only credential. Do not expose the database owner credential to any long-running service.
8. **Run positive and negative privilege checks.** Record normal backend health/write/read behavior, Metabase startup and dashboard queries, then record negative privilege checks proving that the application runtime cannot run DDL or access `metabase`, Metabase metadata cannot connect to `fulfillment_hub`, and analytics read-only cannot write or read outside approved analytics views.
9. **Run public acceptance and observe.** Verify the loopback/HTTPS deployment boundary as applicable, public authentication, backend health, dashboards, restart behavior, audit attribution, and database errors for the agreed observation window.
10. **Close or roll back.** If any gate fails, stop affected services, restore the last known-good consumer configuration, explicitly re-enable the preserved old login only under rollback authorization, and restore the verified backup if data/schema changes require it. Revoke and later drop the old role only after the observation window and a separately approved cleanup.

## Release evidence

The release record must include:

- target volume identity and pre/post inventory;
- backup checksum and restore-drill result;
- reviewed operator-script digest and execution transcript with secrets redacted;
- per-service role mapping and secret-version identifiers;
- positive checks, negative privilege checks, public acceptance, observation result, and rollback disposition.

Changing environment variables does not migrate existing roles or grants. A clean-volume initialization test also does not prove that the existing `postgres-data` volume has passed this migration gate.
