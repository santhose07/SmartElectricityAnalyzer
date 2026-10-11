import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class ReadAppliance {
    public static void main(String[] args) {
        try {
            Class.forName("org.sqlite.JDBC");

            Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:../../electricity.db"
            );

            Statement statement = connection.createStatement();

            ResultSet result = statement.executeQuery(
                "SELECT * FROM appliances"
            );

            while (result.next()) {
                System.out.println("ID: " + result.getInt("id"));
                System.out.println("Appliance: " + result.getString("appliance_name"));
                System.out.println("Power: " + result.getDouble("power_watts") + " W");
                System.out.println("Hours/Day: " + result.getDouble("hours_per_day"));
                System.out.println("Units/Month: " + result.getDouble("units_per_month"));
                System.out.println("Cost/Month: Rs." + result.getDouble("cost_per_month"));
                System.out.println("----------------------------");
            }

            result.close();
            statement.close();
            connection.close();

        } catch (Exception e) {
            System.out.println("Data reading failed!");
            e.printStackTrace();
        }
    }
}