import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;

public class InsertAppliance {
    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");

            Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:../../electricity.db"
            );

            String sql = """
                INSERT INTO appliances
                (appliance_name, power_watts, hours_per_day, units_per_month, cost_per_month)
                VALUES (?, ?, ?, ?, ?)
                """;

            PreparedStatement statement = connection.prepareStatement(sql);

            statement.setString(1, "Fan");
            statement.setDouble(2, 75);
            statement.setDouble(3, 8);
            statement.setDouble(4, 18);
            statement.setDouble(5, 90);

            statement.executeUpdate();

            System.out.println("Appliance data inserted successfully!");

            statement.close();
            connection.close();

        } catch (Exception e) {
            System.out.println("Data insertion failed!");
            e.printStackTrace();
        }
    }
}