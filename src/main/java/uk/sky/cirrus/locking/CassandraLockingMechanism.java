package uk.sky.cirrus.locking;

import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.exceptions.WriteTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.sky.cirrus.locking.exception.CannotAcquireLockException;
import uk.sky.cirrus.locking.exception.CannotReleaseLockException;

public class CassandraLockingMechanism extends LockingMechanism {

    private static final Logger log = LoggerFactory.getLogger(CassandraLockingMechanism.class);

    private final Session session;
    private final CassandraLockConfig lockConfig;

    private PreparedStatement insertLockQuery;
    private PreparedStatement deleteLockQuery;
    private boolean isRetryAfterWriteTimeout = false;

    public CassandraLockingMechanism(Session session, String keyspace, CassandraLockConfig lockConfig) {
        super(keyspace + ".schema_migration", lockConfig.getClientId());
        this.session = session;
        this.lockConfig = lockConfig;
    }

    @Override
    public void init() {
        super.init();

        try {
            Row locksKeyspace = session.execute("SELECT keyspace_name FROM system.schema_keyspaces WHERE keyspace_name = 'locks'").one();

            if (locksKeyspace == null) {
                session.execute(String.format("CREATE KEYSPACE IF NOT EXISTS locks WITH replication = {%s}", lockConfig.getReplicationString()));
                session.execute("CREATE TABLE IF NOT EXISTS locks.locks (name text PRIMARY KEY, client text)");
            }

            insertLockQuery = session.prepare("INSERT INTO locks.locks (name, client) VALUES (?, ?) IF NOT EXISTS");
            deleteLockQuery = session.prepare("DELETE FROM locks.locks WHERE name = ? IF client = ?");

        } catch (DriverException e) {
            throw new CannotAcquireLockException("Query to create locks schema failed to execute", e);
        }
    }

    @Override
    public boolean acquire() {
        try {
            ResultSet resultSet = session.execute(insertLockQuery.bind(lockName, clientId));
            Row currentLock = resultSet.one();
            if (currentLock.getBool("[applied]") || clientId.equals(currentLock.getString("client"))) {
                return true;
            } else {
                log.info("Lock currently held by {}", currentLock);
                return false;
            }
        } catch (WriteTimeoutException wte) {
            log.warn("Query to acquire lock for {} failed to execute: {}", clientId, wte.getMessage());
            return false;
        } catch (DriverException de) {
            throw new CannotAcquireLockException(String.format("Query to acquire lock %s for client %s failed to execute", lockName, clientId), de);
        }
    }

    @Override
    public boolean release() {
        while (true) {
            try {
                ResultSet resultSet = session.execute(deleteLockQuery.bind(lockName, clientId));
                Row result = resultSet.one();

                if (result.getBool("[applied]") || !result.getColumnDefinitions().contains("client")) {
                    log.info("Lock released for {} by client {} at: {}", lockName, clientId, System.currentTimeMillis());
                    return true;
                }

                String clientReleasingLock = result.getString("client");
                if (!clientReleasingLock.equals(clientId)) {
                    if (isRetryAfterWriteTimeout) {
                        log.info("Released lock for client {} in retry attempt after WriteTimeoutException", clientReleasingLock);
                        return true;
                    } else {
                        throw new CannotReleaseLockException(
                                String.format("Lock %s attempted to be released by a non lock holder (%s). Current lock holder: %s", lockName, clientId, clientReleasingLock));
                    }
                }

            } catch (WriteTimeoutException e) {
                isRetryAfterWriteTimeout = true;
                waitToRetryRelease();
            } catch (DriverException e) {
                log.error("Query to release lock failed to execute for {} by client {}", lockName, clientId, e);
                throw new CannotReleaseLockException("Query failed to execute", e);
            }
        }
    }

    private void waitToRetryRelease() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            log.warn("Thread sleep interrupted with {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
