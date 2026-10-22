package com.elma.gohan.provider.evidence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/** 出站执行器和补数消费者各占最多一条连接，不占推荐业务连接池。 */
@Component
public class BaiduDatabase implements AutoCloseable {
    private final HikariDataSource pool;

    public BaiduDatabase(HikariDataSource businessPool) {
        HikariConfig config = new HikariConfig();
        businessPool.copyStateTo(config);
        config.setPoolName("baidu-coordination");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(250);
        config.setInitializationFailTimeout(-1);
        config.setAutoCommit(true);
        this.pool = new HikariDataSource(config);
    }

    public Connection open() throws SQLException {
        return pool.getConnection();
    }

    @Override
    @PreDestroy
    public void close() {
        pool.close();
    }
}
