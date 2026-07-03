# CDC with Debezium Outbox

## Conclusion
The comment is correct.

Using `@Scheduled` polling on `outbox_event` is acceptable for a demo or moderate throughput system, but at very large scale it can:
- increase repetitive database reads
- create contention on the outbox table
- add publish latency because delivery waits for the next poll cycle
- waste resources during idle periods

## Recommended production direction
Use Debezium CDC to read PostgreSQL WAL directly and publish outbox rows to Kafka.

## What changed in this project
1. Added `kafka-connect` service with Debezium image in `docker-compose.yml`
2. Enabled PostgreSQL logical replication with `wal_level=logical`
3. Added Debezium connector JSON files for all 5 service databases
4. Switched service config to `lending.outbox.mode=cdc`
5. Kept `OutboxPublisher` only for optional polling mode:
   - it now runs only when `lending.outbox.mode=polling`

## Flow
1. Service writes business row + `outbox_event` in one DB transaction
2. Debezium reads WAL
3. Outbox Event Router publishes to Kafka topic from `topic_name`
4. `aggregate_id` becomes the Kafka message key
5. Consumers continue the saga as before

## Notes
- This update changes the project toward a CDC-based architecture
- It is still a demo skeleton; Kafka Connect / Debezium runtime was not executed here
