package com.system.morapack.dao.morapack_psql.service;

import com.system.morapack.dao.morapack_psql.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * High-performance batch insert service for orders using JDBC
 * Bypasses JPA/Hibernate overhead for bulk operations
 */
@Service
@RequiredArgsConstructor
public class OrderBatchInsertService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Batch insert orders using JDBC (much faster than JPA for bulk operations)
     *
     * @param orders List of orders to insert
     * @param batchSize Size of each batch (recommended: 1000-5000)
     * @return Number of orders inserted
     */
    @Transactional
    public int batchInsertOrders(List<Order> orders, int batchSize) {
        String sql = """
            INSERT INTO orders
            (name, origin_city_id, destination_city_id, delivery_date,
             status, pickup_time_hours, creation_date, customer_id, updated_at)
            VALUES (?, ?, ?, ?, ?::package_status, ?, ?, ?, ?)
            """;

        int totalInserted = 0;

        // Process in batches
        for (int i = 0; i < orders.size(); i += batchSize) {
            int end = Math.min(i + batchSize, orders.size());
            List<Order> batch = orders.subList(i, end);

            int[] results = jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int index) throws SQLException {
                    Order order = batch.get(index);
                    ps.setString(1, order.getName());
                    ps.setInt(2, order.getOrigin().getId());
                    ps.setInt(3, order.getDestination().getId());
                    ps.setTimestamp(4, Timestamp.valueOf(order.getDeliveryDate()));
                    ps.setString(5, order.getStatus().name());
                    ps.setDouble(6, order.getPickupTimeHours());
                    ps.setTimestamp(7, Timestamp.valueOf(order.getCreationDate()));
                    ps.setInt(8, order.getCustomer().getId());
                    ps.setTimestamp(9, Timestamp.valueOf(order.getCreationDate())); // updated_at
                }

                @Override
                public int getBatchSize() {
                    return batch.size();
                }
            });

            totalInserted += results.length;

            // Log progress
            if (i % 100000 == 0) {
                System.out.println("Inserted " + totalInserted + " / " + orders.size() + " orders...");
            }
        }

        System.out.println("Total orders inserted: " + totalInserted);
        return totalInserted;
    }
}
