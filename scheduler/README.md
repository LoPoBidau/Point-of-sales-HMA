# Stock Posting Scheduler (Ktor)

A lightweight Ktor server that periodically scans the pending_stock_receipts collection and posts stock once the selected scheduledAt timestamp has passed.

## Prerequisites

- JDK 17+
- Google Cloud service account with Firestore access.
- GOOGLE_APPLICATION_CREDENTIALS environment variable pointing to the service-account JSON key (or rely on default credentials when running in GCP).

## Configuration

Edit src/main/resources/application.conf as needed:

`
scheduling {
  intervalMs = 60000   # how often the worker checks Firestore
  batchLimit = 20      # max documents processed per cycle
}

firebase {
  projectId = "your-project-id" # optional; leave empty to use default credentials
}
`

## Running locally

`
cd scheduler
./gradlew run
`

The server exposes:

- GET /health ? returns OK.
- GET /metrics/pending ? rough count of pending documents.

## Deploying

The app is designed to run on any JVM host (Cloud Run, App Engine, VM, or on-prem). Ensure GOOGLE_APPLICATION_CREDENTIALS (or other ADC source) and the config file are provided. The background coroutine will automatically kick in when the server boots and will keep Firestore in sync.
