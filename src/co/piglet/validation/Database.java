package co.piglet.validation;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

public class Database {

    private final String connectionString = "jdbc:mysql://localhost/piglet";
    private Logger logger;
    private String username;
    private String password;

    public Database(Logger logger) {

        this.logger = logger;

        Properties prop = new Properties();

        try {
            FileInputStream fis = new FileInputStream("permissions.properties");
            prop.load(fis);
            username = prop.getProperty("username");
            password = prop.getProperty("password");
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public int getPlayerByAuthKey(String authKey) {
        int playerID = 0;

        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("SELECT id FROM user WHERE authKey = ?");
            statement.setString(1, authKey);

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                playerID = result.getInt("id");
            }

            connection.close();

            return playerID;
        } catch (Exception e) {
            logger.info("exception in getPlayerByAuthKey() " + e.getLocalizedMessage());
        }

        return 0;
    }

    public String checkAuthorization(String username, String password) {
        String minecraftUser = "";

        try {
            Connection connection = DriverManager.getConnection(connectionString, this.username, this.password);
            PreparedStatement statement = connection.prepareStatement("SELECT m.name FROM user u JOIN minecraft_user m ON u.id = m.user_id WHERE u.name = ? AND u.password = ?;");
            statement.setString(1, username);
            statement.setString(2, password);

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                minecraftUser = result.getString("name");
            }

            connection.close();
            return minecraftUser;
        } catch (Exception e) {
            logger.info("exception in checkAuthorization() " + e.getLocalizedMessage());
        }

        return minecraftUser;
    }

    public int getUserIDByMinecraftName(String name) {
        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("SELECT user_id FROM minecraft_user WHERE name = ?");
            statement.setString(1, name);

            ResultSet result = statement.executeQuery();

            if (result.next()) {
                int userID = result.getInt("user_id");
                connection.close();
                return userID;
            }

        } catch (SQLException e) {
            logger.info("exception in getUserIDByMinecraftName() " + e.getLocalizedMessage());
        }

        return 0;
    }

    public boolean checkMinecraftAccountNotLinked(String accountName) {
        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM minecraft_user WHERE name = ?");
            statement.setString(1, accountName);

            ResultSet result = statement.executeQuery();

            return result.next();
        } catch (SQLException e) {
            logger.info("exception in checkMinecraftAccountNotLinked() " + e.getLocalizedMessage());
        }

        return true;
    }

    public boolean linkMinecraftAccount(String accountName, int playerID) {
        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("INSERT INTO minecraft_user (user_id, name, link_date) VALUES (?, ?, NOW())");
            statement.setInt(1, playerID);
            statement.setString(2, accountName);

            int outcome = statement.executeUpdate();

            return outcome == 1;
        } catch (Exception e) {
            logger.info("exception in linkMinecraftAccount() " + e.getLocalizedMessage());
        }

        return false;
    }

    public ResultSet getNextFreeVault() {
        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("SELECT v.id, v.x, v.y, v.z FROM vault v LEFT JOIN vault_owner vo ON v.id = vo.vault_id WHERE vo.user_id IS NULL ORDER BY v.y DESC LIMIT 1");

            return statement.executeQuery();
        } catch (SQLException e) {
            logger.info("exception in getNextFreeVault() " + e.getLocalizedMessage());
        }

        return null;
    }

    public boolean allocateVault(int userID, int vaultID) {
        try {
            Connection connection = DriverManager.getConnection(connectionString, username, password);
            PreparedStatement statement = connection.prepareStatement("INSERT INTO vault_owner (vault_id, user_id) VALUES (?, ?)");

            statement.setInt(1, vaultID);
            statement.setInt(2, userID);

            boolean allocationOK = statement.executeUpdate() == 1;
            connection.close();

            return allocationOK;
        } catch (SQLException e) {
            logger.info("exception in getNextFreeVault() " + e.getLocalizedMessage());
        }

        return false;
    }
}
