#!/bin/bash

# CHIẾN THẦN HỦY DIỆT (CHAOS ENGINEERING SCRIPT)
# Mục tiêu: Kiểm tra khả năng Failover của Saga Orchestrator & Debezium CDC

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

echo -e "${GREEN}Starting Chaos Engineering Mission...${NC}"

# SCENARIO 1: Đập chết Kafka Broker số 1 khi đang chạy tải
kill_kafka_broker() {
    echo -e "${RED}[CHAOS] Killing Kafka Broker 1...${NC}"
    docker stop kafka
    sleep 30
    echo -e "${GREEN}[AUTORE COVERY CHECK] Kiểm tra Logs App xem có tự switch sang Kafka-2/3 không...${NC}"
    docker start kafka
}

# SCENARIO 2: Đập chết Debezium Connector (Gây nghẽn CDC)
break_cdc_outbox() {
    echo -e "${RED}[CHAOS] Deleting Loan Outbox Connector (Simulate Debezium Failure)...${NC}"
    curl -X DELETE http://localhost:8083/connectors/loan-outbox-connector-shard1
    echo -e "${RED}[SYSTEM BLOCK] DB vẫn ghi đều, nhưng Kafka sẽ không có Message trong 2 phút...${NC}"
    sleep 120
    echo -e "${GREEN}[RECOVERY] Re-deploying Debezium Connector. Kiểm tra Backlog recovery...${NC}"
    curl -X POST -H "Content-Type: application/json" --data @debezium/loan-outbox-connector-shard1.json http://localhost:8083/connectors
}

# SCENARIO 3: Kill App Orchestrator Instance
kill_orchestrator() {
    echo -e "${RED}[CHAOS] Killing Orchestrator Service...${NC}"
    docker stop loan-orchestrator-service
    sleep 10
    echo -e "${GREEN}[RECOVERY] Restarting Orchestrator. Kiểm tra xem ShedLock có tự cứu data không...${NC}"
    docker start loan-orchestrator-service
}

case "$1" in
    kafka) kill_kafka_broker ;;
    cdc) break_cdc_outbox ;;
    orchestrator) kill_orchestrator ;;
    *) echo "Usage: $0 {kafka|cdc|orchestrator}" ;;
esac
