package com.example.lending.loan.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

// Cấu hình Nối 2 DB (Shards) vào trong 1 App
@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.datasource.shard1")
    public DataSource shard1DataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.datasource.shard2")
    public DataSource shard2DataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.datasource.shard1-replica")
    public DataSource shard1ReplicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties(prefix = "app.datasource.shard2-replica")
    public DataSource shard2ReplicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Primary
    public DataSource primaryDataSource() {
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put("SHARD_1", shard1DataSource());
        targetDataSources.put("SHARD_2", shard2DataSource());
        targetDataSources.put("SHARD_REPLICA_1", shard1ReplicaDataSource());
        targetDataSources.put("SHARD_REPLICA_2", shard2ReplicaDataSource());
        
        routingDataSource.setTargetDataSources(targetDataSources);
        // Mặc định gọi vào Shard 1 nếu chưa chọn Shard (để Hibernate bootup an toàn)
        routingDataSource.setDefaultTargetDataSource(shard1DataSource());
        return routingDataSource;
    }
}
