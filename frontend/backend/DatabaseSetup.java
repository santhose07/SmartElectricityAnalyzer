import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseSetup {
    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");

            Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:../../electricity.db"
            );

            Statement statement = connection.createStatement();
            statement.executeUpdate("DROP TABLE IF EXISTS appliances");

            String sql = """
                CREATE TABLE IF NOT EXISTS appliances (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    appliance_name TEXT NOT NULL,
                    power_watts REAL NOT NULL,
                    hours_per_day REAL NOT NULL,
                    units_per_month REAL NOT NULL,
                    cost_per_month REAL NOT NULL
                )
                """;

            statement.executeUpdate(sql);

            System.out.println("Appliances table created successfully!");

            statement.close();
            connection.close();

        } catch (Exception e) {
            System.out.println("Database setup failed!");
            e.printStackTrace();
        }
    }
}