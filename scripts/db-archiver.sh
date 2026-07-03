#!/bin/bash
# Script dọn dẹp các Outbox đã xử lý xong (Tránh treo DB sau n triệu user)
echo "🚀 Starting DB Archiving for million-user load..."
psql -h $PG_HOST -U $PG_USER -d $PG_DB -c "DELETE FROM outbox_event WHERE status = 'PROCESSED' AND created_at < NOW() - INTERVAL '3 days';"
echo "✅ Finished archiving."
