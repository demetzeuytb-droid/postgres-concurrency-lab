package io.github.demetzeu.lab;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Main {

    public static void main(String[] args) throws SQLException, InterruptedException, ExecutionException, TimeoutException {

        try (Connection connection = openConnection()) {

            createAccountsTable(connection);
            seedAccounts(connection);
            resetDemoAccounts(connection);

            System.out.println("BEFORE TRANSFER");
            printAccounts(connection);

            long totalBefore = totalBalance(connection);

            transfer(1, 2, 10_000);

            System.out.println("\nAFTER TRANSFER");
            printAccounts(connection);

            long totalAfter = totalBalance(connection);

            System.out.println("\nTotal before: " + money(totalBefore));
            System.out.println("Total after:  " + money(totalAfter));

            if (totalBefore != totalAfter) {
                throw new IllegalStateException("Verification failed: total balance changed.");
            }

            System.out.println("Verification passed: total balance is unchanged.");

            demonstrateRollback(connection);
            demonstrateConcurrentTransfers(connection);
            demonstrateDeadlock(connection);
            demonstrateIsolationLevels(connection);

        }

    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(requiredEnvironmentVariable("LAB_DB_URL"), requiredEnvironmentVariable("LAB_DB_USER"), requiredEnvironmentVariable("LAB_DB_PASSWORD"));
    }

    private static void createAccountsTable(Connection connection)
            throws SQLException {

        String sql = "CREATE TABLE IF NOT EXISTS accounts (id BIGINT PRIMARY KEY, owner_name VARCHAR(100) NOT NULL, balance_minor_units BIGINT NOT NULL CHECK (balance_minor_units >= 0))";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void seedAccounts(Connection connection) throws SQLException {

        String sql = "INSERT INTO accounts (id, owner_name, balance_minor_units) VALUES (1, 'Andrei', 100000), (2, 'Mihai', 100000) ON CONFLICT (id) DO NOTHING";

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }

    }

    private static void resetDemoAccounts(Connection connection) throws SQLException {

        String sql = "UPDATE accounts SET balance_minor_units = 100000 WHERE id IN (1, 2)";

        try (Statement statement = connection.createStatement()) {

            if (statement.executeUpdate(sql) != 2) {
                throw new SQLException("Expected to reset both demo accounts.");
            }

        }

        System.out.println("Demo accounts reset: Andrei and Mihai each start with 1000.00 RON.");

    }

    private static void demonstrateIsolationLevels(Connection connection) throws SQLException, InterruptedException, ExecutionException, TimeoutException {

        int[] isolationLevels = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE};
        String[] isolationNames = {"READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"};

        try {

            for (int index = 0; index < isolationLevels.length; index++) {

                resetDemoAccounts(connection);
                demonstrateRepeatedRead(connection, isolationLevels[index], isolationNames[index]);
                demonstrateWriteSkew(connection, isolationLevels[index], isolationNames[index]);

            }

        } finally {

            resetDemoAccounts(connection);

        }

    }

    private static void demonstrateRepeatedRead(Connection observer, int isolationLevel, String isolationName) throws SQLException {

        System.out.println("\nREPEATED READ: " + isolationName);
        long totalBefore = totalBalance(observer);

        try (Connection reader = openConnection()) {

            reader.setTransactionIsolation(isolationLevel);
            reader.setAutoCommit(false);

            try {

                long firstRead = accountBalance(reader, 1);

                // A separate connection commits while the reader transaction stays open.
                transfer(1, 2, 10_000);

                long secondRead = accountBalance(reader, 1);
                long committedBalance = accountBalance(observer, 1);
                long expectedSecondRead = isolationLevel == Connection.TRANSACTION_READ_COMMITTED ? firstRead - 10_000 : firstRead;

                System.out.println("First read: " + money(firstRead));
                System.out.println("Second read in the same transaction: " + money(secondRead));
                System.out.println("Current committed balance: " + money(committedBalance));

                if (secondRead != expectedSecondRead || committedBalance != firstRead - 10_000 || totalBalance(observer) != totalBefore) {
                    throw new IllegalStateException("Verification failed: unexpected repeated-read behavior at " + isolationName);
                }

                reader.commit();
                System.out.println("Verification passed: repeated reads match " + isolationName + " semantics.");

            } catch (SQLException | RuntimeException exception) {

                rollbackAfterFailure(reader, exception);
                throw exception;

            }

        }

    }

    private static void demonstrateWriteSkew(Connection observer, int isolationLevel, String isolationName) throws SQLException, InterruptedException, ExecutionException, TimeoutException {

        System.out.println("\nWRITE SKEW: " + isolationName);
        System.out.println("Separate reserve scenario: at least one reserve must remain available.");

        try (Statement statement = observer.createStatement()) {

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS demo_reserves (id BIGINT PRIMARY KEY, owner_name VARCHAR(100) NOT NULL, available BOOLEAN NOT NULL)");
            statement.executeUpdate("INSERT INTO demo_reserves (id, owner_name, available) VALUES (1, 'Andrei', TRUE), (2, 'Mihai', TRUE) ON CONFLICT (id) DO UPDATE SET available = TRUE");

        }

        CyclicBarrier bothChecked = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean firstCommitted;
        boolean secondCommitted;

        try {

            Future<Boolean> first = executor.submit(() -> releaseReserve(1, 2, isolationLevel, bothChecked));
            Future<Boolean> second = executor.submit(() -> releaseReserve(2, 1, isolationLevel, bothChecked));

            firstCommitted = first.get(25, TimeUnit.SECONDS);
            secondCommitted = second.get(25, TimeUnit.SECONDS);

        } finally {

            executor.shutdownNow();

            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Reserve workers did not stop in time.");
            }

        }

        long availableCount;
        String sql = "SELECT COUNT(*) AS available_count FROM demo_reserves WHERE id IN (1, 2) AND available";

        try (Statement statement = observer.createStatement(); ResultSet result = statement.executeQuery(sql)) {

            if (!result.next()) {
                throw new SQLException("Reserve count query returned no result.");
            }

            availableCount = result.getLong("available_count");

        }

        int committedCount = (firstCommitted ? 1 : 0) + (secondCommitted ? 1 : 0);
        int expectedCommitted = isolationLevel == Connection.TRANSACTION_SERIALIZABLE ? 1 : 2;

        if (committedCount != expectedCommitted || availableCount != 2 - expectedCommitted) {
            throw new IllegalStateException("Verification failed: unexpected write-skew outcome at " + isolationName);
        }

        System.out.println("Committed reserve releases: " + committedCount);
        System.out.println("Available reserves: " + availableCount);
        System.out.println(availableCount == 0 ? "Observed anomaly: the shared reserve rule was violated." : "Shared reserve rule preserved; the conflicting transaction was rejected.");
        System.out.println("Verification passed: write-skew behavior matches " + isolationName + " semantics.");

    }

    private static boolean releaseReserve(long ownId, long otherId, int isolationLevel, CyclicBarrier bothChecked) throws SQLException, InterruptedException, BrokenBarrierException, TimeoutException {

        try (Connection connection = openConnection()) {

            connection.setTransactionIsolation(isolationLevel);
            connection.setAutoCommit(false);

            try {

                try (Statement statement = connection.createStatement()) {

                    statement.execute("SET LOCAL lock_timeout = '8s'");
                    statement.execute("SET LOCAL statement_timeout = '10s'");

                }

                String checkSql = "SELECT available FROM demo_reserves WHERE id = ?";

                try (PreparedStatement statement = connection.prepareStatement(checkSql)) {

                    statement.setLong(1, otherId);
                    statement.setQueryTimeout(10);

                    try (ResultSet result = statement.executeQuery()) {

                        if (!result.next() || !result.getBoolean("available")) {
                            throw new IllegalStateException("The other reserve must be available before release.");
                        }

                    }

                }

                System.out.println("Reserve " + ownId + " checked that reserve " + otherId + " is available.");
                bothChecked.await(10, TimeUnit.SECONDS);

                String updateSql = "UPDATE demo_reserves SET available = FALSE WHERE id = ?";

                try (PreparedStatement statement = connection.prepareStatement(updateSql)) {

                    statement.setLong(1, ownId);
                    statement.setQueryTimeout(10);

                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Expected to release exactly one reserve.");
                    }

                }

                connection.commit();
                System.out.println("Reserve " + ownId + " release committed.");
                return true;

            } catch (SQLException exception) {

                rollbackAfterFailure(connection, exception);

                if (!"40001".equals(exception.getSQLState()) || exception.getSuppressed().length > 0) {
                    throw exception;
                }

                System.out.println("Reserve " + ownId + " release rejected with SQLSTATE 40001; transaction rolled back.");
                return false;

            } catch (InterruptedException | BrokenBarrierException | TimeoutException | RuntimeException exception) {

                rollbackAfterFailure(connection, exception);

                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                throw exception;

            }

        }

    }

    private static void rollbackAfterFailure(Connection connection, Exception exception) {

        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            exception.addSuppressed(rollbackException);
        }

    }

    private static void transfer(long sourceId, long destinationId, long amountMinorUnits) throws SQLException {

        transfer(sourceId, destinationId, amountMinorUnits, false);

    }

    private static void transfer(long sourceId, long destinationId, long amountMinorUnits, boolean failAfterDebit) throws SQLException {

        transfer(sourceId, destinationId, amountMinorUnits, failAfterDebit, null, null);

    }

    private static void transfer(long sourceId, long destinationId, long amountMinorUnits, boolean failAfterDebit, CountDownLatch ready, CountDownLatch start) throws SQLException {

        if (sourceId == destinationId) {
            throw new IllegalArgumentException("Source and destination must be different.");
        }

        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }

        try (Connection connection = openConnection()) {

            if (ready != null && start != null) {

                ready.countDown();

                try {

                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for the concurrent start signal.");
                    }

                } catch (InterruptedException exception) {

                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Transfer interrupted before starting.", exception);

                }

            }

            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            try {

                long firstId = Math.min(sourceId, destinationId);
                long secondId = Math.max(sourceId, destinationId);

                long firstBalance = lockAccount(connection, firstId);
                long secondBalance = lockAccount(connection, secondId);

                long sourceBalance = sourceId == firstId ? firstBalance : secondBalance;

                if (sourceBalance < amountMinorUnits) {
                    throw new IllegalArgumentException("Insufficient funds in account " + sourceId);
                }

                changeBalance(connection, sourceId, -amountMinorUnits);

                if (failAfterDebit) {
                    throw new SimulatedTransferException();
                }

                changeBalance(connection, destinationId, amountMinorUnits);

                connection.commit();

            } catch (SQLException | RuntimeException exception) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }

                throw exception;

            }

        }

        System.out.println("\nTransferred " + money(amountMinorUnits) + " from account " + sourceId + " to account " + destinationId);

    }

    private static void demonstrateConcurrentTransfers(Connection connection) throws SQLException, InterruptedException, ExecutionException, TimeoutException {

        System.out.println("\nBEFORE CONCURRENT TRANSFERS");
        printAccounts(connection);

        long sourceBefore = accountBalance(connection, 1);
        long destinationBefore = accountBalance(connection, 2);
        long totalBefore = totalBalance(connection);

        if (sourceBefore < 10_000 || destinationBefore < 5_000) {
            throw new IllegalStateException("Both accounts need sufficient funds before the concurrent demonstration.");
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {

            Future<Void> firstTransfer = executor.submit(() -> {

                transfer(1, 2, 10_000, false, ready, start);
                return null;

            });

            Future<Void> secondTransfer = executor.submit(() -> {

                transfer(2, 1, 5_000, false, ready, start);
                return null;

            });

            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new TimeoutException("Both transfer connections were not ready in time.");
            }

            System.out.println("Both worker connections are ready. Starting transfers.");
            start.countDown();

            firstTransfer.get(20, TimeUnit.SECONDS);
            secondTransfer.get(20, TimeUnit.SECONDS);

        } finally {

            executor.shutdownNow();

            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Transfer workers did not stop in time.");
            }

        }

        System.out.println("\nAFTER CONCURRENT TRANSFERS");
        printAccounts(connection);

        long sourceAfter = accountBalance(connection, 1);
        long destinationAfter = accountBalance(connection, 2);
        long totalAfter = totalBalance(connection);

        if (sourceAfter != sourceBefore - 5_000 || destinationAfter != destinationBefore + 5_000 || totalBefore != totalAfter) {
            throw new IllegalStateException("Verification failed: concurrent transfers produced unexpected balances.");
        }

        System.out.println("Verification passed: both concurrent transfers committed with the expected balances and unchanged total.");

    }

    private static void demonstrateDeadlock(Connection connection) throws SQLException, InterruptedException, ExecutionException, TimeoutException {

        System.out.println("\nBEFORE DEADLOCK DEMONSTRATION");
        printAccounts(connection);

        long firstBefore = accountBalance(connection, 1);
        long secondBefore = accountBalance(connection, 2);
        long totalBefore = totalBalance(connection);
        long amountMinorUnits = 10_000;

        if (firstBefore < amountMinorUnits || secondBefore < amountMinorUnits) {
            throw new IllegalStateException("Both accounts need at least 100 RON for the deadlock demonstration.");
        }

        String sql = "SELECT EXTRACT(EPOCH FROM current_setting('deadlock_timeout')::interval) AS timeout_seconds";

        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {

            if (!result.next() || result.getDouble("timeout_seconds") >= 8) {
                throw new IllegalStateException("Deadlock detection must run before the demonstration's 8-second lock timeout.");
            }

        }

        CyclicBarrier firstLocksHeld = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean firstCommitted;
        boolean secondCommitted;

        try {

            Future<Boolean> firstTransfer = executor.submit(() -> deadlockTransfer(1, 2, amountMinorUnits, firstLocksHeld));
            Future<Boolean> secondTransfer = executor.submit(() -> deadlockTransfer(2, 1, amountMinorUnits, firstLocksHeld));

            firstCommitted = firstTransfer.get(25, TimeUnit.SECONDS);
            secondCommitted = secondTransfer.get(25, TimeUnit.SECONDS);

        } finally {

            executor.shutdownNow();

            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Deadlock workers did not stop in time.");
            }

        }

        if (firstCommitted == secondCommitted) {
            throw new IllegalStateException("Verification failed: expected one committed transfer and one deadlock victim.");
        }

        System.out.println("\nAFTER DEADLOCK DEMONSTRATION");
        printAccounts(connection);

        long expectedChange = firstCommitted ? -amountMinorUnits : amountMinorUnits;

        if (accountBalance(connection, 1) != firstBefore + expectedChange || accountBalance(connection, 2) != secondBefore - expectedChange || totalBalance(connection) != totalBefore) {
            throw new IllegalStateException("Verification failed: balances do not match the surviving transfer.");
        }

        System.out.println("Verification passed: one transfer committed, one was rolled back with SQLSTATE 40P01, and the total is unchanged.");

    }

    private static boolean deadlockTransfer(long sourceId, long destinationId, long amountMinorUnits, CyclicBarrier firstLocksHeld) throws SQLException, InterruptedException, BrokenBarrierException, TimeoutException {

        try (Connection connection = openConnection()) {

            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);

            try {

                try (Statement statement = connection.createStatement()) {

                    statement.execute("SET LOCAL lock_timeout = '8s'");
                    statement.execute("SET LOCAL statement_timeout = '10s'");

                }

                long sourceBalance = lockAccount(connection, sourceId);
                System.out.println("Transfer " + sourceId + " -> " + destinationId + " holds account " + sourceId);

                if (sourceBalance < amountMinorUnits) {
                    throw new IllegalArgumentException("Insufficient funds in account " + sourceId);
                }

                firstLocksHeld.await(10, TimeUnit.SECONDS);

                System.out.println("Transfer " + sourceId + " -> " + destinationId + " requests account " + destinationId);
                lockAccount(connection, destinationId);

                changeBalance(connection, sourceId, -amountMinorUnits);
                changeBalance(connection, destinationId, amountMinorUnits);
                connection.commit();

                System.out.println("Transfer " + sourceId + " -> " + destinationId + " committed.");
                return true;

            } catch (SQLException exception) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                    throw exception;
                }

                if (!"40P01".equals(exception.getSQLState())) {
                    throw exception;
                }

                System.out.println("Deadlock victim: transfer " + sourceId + " -> " + destinationId + "; SQLSTATE " + exception.getSQLState() + "; transaction rolled back.");
                return false;

            } catch (InterruptedException | BrokenBarrierException | TimeoutException | RuntimeException exception) {

                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }

                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                throw exception;

            }

        }

    }

    private static void demonstrateRollback(Connection connection) throws SQLException {

        System.out.println("\nBEFORE FAILED TRANSFER");
        printAccounts(connection);

        long sourceBefore = accountBalance(connection, 1);
        long destinationBefore = accountBalance(connection, 2);
        long totalBefore = totalBalance(connection);
        boolean failureObserved = false;

        try {

            transfer(1, 2, 10_000, true);

        } catch (SimulatedTransferException exception) {

            if (exception.getSuppressed().length > 0) {
                throw exception;
            }

            failureObserved = true;
            System.out.println("Expected failure: " + exception.getMessage());

        }

        if (!failureObserved) {
            throw new IllegalStateException("Verification failed: simulated failure did not occur.");
        }

        System.out.println("\nAFTER ROLLBACK");
        printAccounts(connection);

        long sourceAfter = accountBalance(connection, 1);
        long destinationAfter = accountBalance(connection, 2);
        long totalAfter = totalBalance(connection);

        if (sourceBefore != sourceAfter || destinationBefore != destinationAfter || totalBefore != totalAfter) {
            throw new IllegalStateException("Verification failed: rollback changed account balances.");
        }

        System.out.println("Verification passed: rollback preserved both account balances and the total.");

    }

    private static long accountBalance(Connection connection, long accountId) throws SQLException {

        String sql = "SELECT balance_minor_units FROM accounts WHERE id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);
            statement.setQueryTimeout(10);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new IllegalArgumentException("Account does not exist: " + accountId);
                }

                return result.getLong("balance_minor_units");

            }

        }

    }

    private static class SimulatedTransferException extends RuntimeException {

        private SimulatedTransferException() {
            super("Simulated failure after debit and before credit.");
        }

    }

    private static long lockAccount(Connection connection, long accountId) throws SQLException {

        String sql = "SELECT balance_minor_units FROM accounts WHERE id = ? FOR UPDATE";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, accountId);
            statement.setQueryTimeout(10);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    throw new IllegalArgumentException("Account does not exist: " + accountId);
                }

                return result.getLong("balance_minor_units");

            }

        }

    }


    private static void changeBalance(Connection connection, long accountId, long changeMinorUnits) throws SQLException {

        String sql = "UPDATE accounts SET balance_minor_units = balance_minor_units + ? WHERE id = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, changeMinorUnits);
            statement.setLong(2, accountId);
            statement.setQueryTimeout(10);

            int updatedRows = statement.executeUpdate();

            if (updatedRows != 1) {
                throw new SQLException("Expected to update one account, updated " + updatedRows);
            }

        }

    }

    private static void printAccounts(Connection connection) throws SQLException {

        String sql = "SELECT id, owner_name, balance_minor_units FROM accounts ORDER BY id";

        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {

            while (result.next()) {
                System.out.printf("%d | %s | %s%n", result.getLong("id"), result.getString("owner_name"), money(result.getLong("balance_minor_units")));
            }

        }

    }

    private static long totalBalance(Connection connection)
            throws SQLException {

        String sql = "SELECT COALESCE(SUM(balance_minor_units), 0) AS total FROM accounts";

        try (Statement statement = connection.createStatement();
             
             ResultSet result = statement.executeQuery(sql)) {

            if (!result.next()) {
                throw new SQLException("Total balance query returned no result.");
            }

            return result.getLong("total");
        }
    }

    private static String money(long amountMinorUnits) {
        return BigDecimal.valueOf(amountMinorUnits, 2).toPlainString() + " RON";
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name + ". Set it in the IntelliJ run configuration.");
        }

        return value;
    }
}
