package com.example.lending.loan.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

// Router chính của Spring Boot cho phép đổi DB ngay trong Runtime
public class ShardRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return ShardContext.getCurrentShard();
    }
}
