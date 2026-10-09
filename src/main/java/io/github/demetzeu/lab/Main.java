package io.github.demetzeu.lab;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class Main {

    public static void main(String[] args) throws SQLException {

        try (Connection connection = openConnection()) {

            createAccountsTable(connection);
            seedAccounts(connection);

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

    private static void transfer(long sourceId, long destinationId, long amountMinorUnits) throws SQLException {

        transfer(sourceId, destinationId, amountMinorUnits, false);

    }

    private static void transfer(long sourceId, long destinationId, long amountMinorUnits, boolean failAfterDebit) throws SQLException {

        if (sourceId == destinationId) {
            throw new IllegalArgumentException("Source and destination must be different.");
        }

        if (amountMinorUnits <= 0) {
            throw new IllegalArgumentException("Transfer amount must be positive.");
        }

        try (Connection connection = openConnection()) {

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
