# 1. Summary

`rsp.importer` is a Clojure CLI application that imports highway sign images from an S3-compatible object store into a Postgres-backed system.

What it does:
- Lists objects in the `sign` bucket under either `staging/` (default) or `ai/` (when run with `--ai`).
- Downloads each image to a temp file, reads EXIF metadata (date taken + GPS when present), and loads the image.
- Generates a new Snowflake-style numeric image id, resizes the image into multiple variants, uploads the variants back to the `sign` bucket, and copies the original.
- Writes/updates database state:
  - `staging/` mode inserts a row into `sign.highwaysign_staging`.
  - `--ai` mode marks the corresponding row in `sign.highwaysign` as having a processed image.

Image variants are defined in `src/rsp/image.clj` (placeholder/thumbnail/small/medium/large).

# 2. Installation and Configuration

Prereqs:
- Java (the GitHub workflow uses Java 23)
- Clojure CLI (the GitHub workflow uses CLI `1.11.3.1463`)
- Access to:
  - an S3-compatible endpoint (bucket: `sign`)
  - a Postgres database with the expected tables

Get dependencies:
```bash
clojure -P
```

Configuration is loaded from `resources/config.edn` via Aero and is driven by environment variables:

- `AWS_ACCESS_KEY_ID`
- `AWS_SECRET_KEY`
- `AWS_ENDPOINT_URL`
- `JDBC_URL` (optional; defaults to `jdbc:postgresql://localhost:5432/rwca`)
- `DB_USERNAME` (optional; defaults to `rwca`)
- `DB_PASSWORD` (optional; defaults to `rwca`)

Run locally:

- Import new images (from `staging/`):
```bash
clojure -M -m rsp.importer
```

- Import edited/AI-processed images (from `ai/`):
```bash
clojure -M -m rsp.importer --ai
```

Notes:
- The S3 bucket name `sign` and region `us-east-1` are currently hard-coded in `src/rsp/importer.clj` and `src/rsp/cloud.clj`.
- Images are written back into the same bucket using keys derived from the numeric image id.

This project assumes:
- Object storage: Cloudflare R2 (S3-compatible). For `AWS_ENDPOINT_URL`, use the value described in Cloudflare's R2 documentation for your account/bucket.
- Source/target behavior: the importer reads from and writes back to the same bucket/prefix layout it uses (it both reads and writes objects).
- Database schema: the tables `sign.highwaysign_staging` and `sign.highwaysign` must already exist (they are managed in a different project).

# 3. GitHub Pipeline

The GitHub Actions workflow is `/.github/workflows/import.yaml`.

Behavior:
- Trigger: manual only (`workflow_dispatch`).
- Runner: `ubuntu-latest`.
- Steps:
  - Checkout.
  - Install Java (Zulu, Java 23).
  - Install Clojure CLI.
  - Run two imports:
    - `clojure -M -m rsp.importer` (imports from `staging/`)
    - `clojure -M -m rsp.importer --ai` (imports from `ai/`)

Secrets/vars expected by the workflow:
- Secrets:
  - `AWS_ACCESS_KEY_ID`
  - `AWS_SECRET_KEY`
  - `JDBC_URL`
  - `DB_USERNAME`
  - `DB_PASSWORD`
- Repository variable:
  - `AWS_ENDPOINT_URL`
