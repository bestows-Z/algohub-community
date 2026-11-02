package com.szpu.algohub.problem;

import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates short database writes only; upstream requests must stay outside this boundary. */
@Component
public class NowcoderWriteTransactions {
    // Application namespace + Nowcoder writer key, shared by all JVMs using this database.
    private static final int NAMESPACE = 1095518024;
    private static final int NOWCODER = 1;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public NowcoderWriteTransactions(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    public <T> T execute(String platform, Supplier<T> work) {
        return tx.execute(status -> {
            if ("NOWCODER".equals(platform)) {
                // Acquire before account/task/problem/solved-row locks. Released on commit or rollback.
                // A transaction-scoped lock also covers newly inserted keys and differing row orders.
                jdbc.queryForList("select pg_advisory_xact_lock(?, ?)", NAMESPACE, NOWCODER);
            }
            return work.get();
        });
    }
}
