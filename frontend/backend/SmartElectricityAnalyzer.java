import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Scanner;

public class SmartElectricityAnalyzer {

    public static void main(String[] args)
            throws IOException, ClassNotFoundException, java.sql.SQLException {

        // SQLite connection
        Class.forName("org.sqlite.JDBC");

        Connection dbConnection = DriverManager.getConnection(
                jdbc:sqlite:/app/electricity.db
        );

        System.out.println("Database connected successfully!");

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
HttpServer server = HttpServer.create(new java.net.InetSocketAddress(port), 0);

        // =========================
        // TEST API
        // =========================
        server.createContext("/test", exchange -> {

            String response = "Java Backend Connected Successfully!";

            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
        });

        // =========================
        // ANALYZE API
        // =========================
        server.createContext("/analyze", exchange -> {

            exchange.getResponseHeaders().add(
                    "Access-Control-Allow-Origin", "*"
            );

            String query = exchange.getRequestURI().getQuery();

            String[] parameters = query.split("&");

            String name = parameters[0].split("=")[1];

            double power =
                    Double.parseDouble(parameters[1].split("=")[1]);

            double hours =
                    Double.parseDouble(parameters[2].split("=")[1]);

            double rate =
                    Double.parseDouble(parameters[3].split("=")[1]);

            if (power <= 0 || hours <= 0 || rate <= 0) {

                String response =
                        "Please enter valid values for Power, Hours and Rate.";

                exchange.sendResponseHeaders(400, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.getResponseBody().close();

                return;
            }

            double monthlyUnits =
                    (power * hours * 30) / 1000;

            double monthlyBill =
                    monthlyUnits * rate;

            String response =
                    "Appliance: " + name +
                    "\nMonthly Usage: " + monthlyUnits + " units" +
                    "\nEstimated Monthly Bill: Rs. " + monthlyBill;

            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.getResponseBody().close();
        });

        // =========================
        // ANALYZE ALL API
        // =========================
        server.createContext("/analyzeAll", exchange -> {

            exchange.getResponseHeaders().add(
                    "Access-Control-Allow-Origin", "*"
            );

            try {

                String query = exchange.getRequestURI().getQuery();

                String data =
                        query.substring(query.indexOf("=") + 1);

                data = java.net.URLDecoder.decode(data, "UTF-8");

                double totalUnits = 0;
                double totalBill = 0;

                String[] applianceData =
                        data.split("\\},\\{");

                for (String appliance : applianceData) {

                    // Appliance name
                    String name = "Appliance";

                    if (appliance.contains("\"name\":\"")) {
                        name = appliance
                                .split("\"name\":\"")[1]
                                .split("\"")[0];
                    }

                    // Power
                    String powerText =
                            appliance.split("\"power\":")[1]
                                    .split(",")[0];

                    // Hours
                    String hoursText =
                            appliance.split("\"hours\":")[1]
                                    .split(",")[0];

                    // Rate
                    String rateText =
                            appliance.split("\"rate\":")[1]
                                    .split("}")[0];

                    double power =
                            Double.parseDouble(powerText);

                    double hours =
                            Double.parseDouble(hoursText);

                    double rate =
                            Double.parseDouble(rateText);

                    double monthlyUnits =
                            (power * hours * 30) / 1000;

                    double monthlyBill =
                            monthlyUnits * rate;

                    // Save appliance data into SQLite
                    String insertSQL =
                            "INSERT INTO appliances " +
                            "(appliance_name, power_watts, hours_per_day, units_per_month, cost_per_month) " +
                            "VALUES (?, ?, ?, ?, ?)";

                    try (PreparedStatement statement =
                                 dbConnection.prepareStatement(insertSQL)) {

                        statement.setString(1, name);
                        statement.setDouble(2, power);
                        statement.setDouble(3, hours);
                        statement.setDouble(4, monthlyUnits);
                        statement.setDouble(5, monthlyBill);

                        statement.executeUpdate();
                    }

                    totalUnits += monthlyUnits;
                    totalBill += monthlyBill;
                }

                double yearlyBill = totalBill * 12;

                String response =
                        "Total Monthly Usage: " + totalUnits + " units" +
                        "\nTotal Monthly Bill: Rs. " + totalBill +
                        "\nTotal Yearly Bill: Rs. " + yearlyBill;

                exchange.sendResponseHeaders(
                        200,
                        response.length()
                );

                exchange.getResponseBody().write(
                        response.getBytes()
                );

                exchange.getResponseBody().close();

            } catch (Exception e) {

                String response =
                        "Database or analysis error: " +
                        e.getMessage();

                exchange.sendResponseHeaders(
                        500,
                        response.length()
                );

                exchange.getResponseBody().write(
                        response.getBytes()
                );

                exchange.getResponseBody().close();

                e.printStackTrace();
            }
        });
        server.createContext("/history", exchange -> {

    exchange.getResponseHeaders().add(
            "Access-Control-Allow-Origin", "*"
    );

    try {

        StringBuilder response = new StringBuilder();

        String sql =
                "SELECT id, appliance_name, power_watts, " +
                "hours_per_day, units_per_month, cost_per_month " +
                "FROM appliances";

        try (java.sql.Statement statement =
                     dbConnection.createStatement();

             java.sql.ResultSet result =
                     statement.executeQuery(sql)) {

            while (result.next()) {

                response.append("ID: ")
                        .append(result.getInt("id"))
                        .append("\n");

                response.append("Appliance: ")
                        .append(result.getString("appliance_name"))
                        .append("\n");

                response.append("Power: ")
                        .append(result.getDouble("power_watts"))
                        .append(" W\n");

                response.append("Hours/Day: ")
                        .append(result.getDouble("hours_per_day"))
                        .append("\n");

                response.append("Units/Month: ")
                        .append(result.getDouble("units_per_month"))
                        .append("\n");

                response.append("Cost/Month: Rs.")
                        .append(result.getDouble("cost_per_month"))
                        .append("\n");

                response.append("----------------------------\n");
            }
        }

        byte[] responseBytes =
                response.toString().getBytes("UTF-8");

        exchange.sendResponseHeaders(
                200,
                responseBytes.length
        );

        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();

    } catch (Exception e) {

        String response =
                "Database history error: " +
                e.getMessage();

        byte[] responseBytes =
                response.getBytes("UTF-8");

        exchange.sendResponseHeaders(
                500,
                responseBytes.length
        );

        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();

        e.printStackTrace();
    }
});
server.createContext("/clearHistory", exchange -> {
        exchange.getResponseHeaders().add(
        "Access-Control-Allow-Origin", "*"
);

    exchange.getResponseHeaders().add(
        "Access-Control-Allow-Private-Network",
        "true"
);

    try {

        String sql = "DELETE FROM appliances";

        try (java.sql.Statement statement =
                     dbConnection.createStatement()) {

            statement.executeUpdate(sql);
        }

        String response = "All appliance history deleted successfully!";

        byte[] responseBytes =
                response.getBytes("UTF-8");

        exchange.sendResponseHeaders(
                200,
                responseBytes.length
        );

        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();

    } catch (Exception e) {

        String response =
                "Delete history error: " + e.getMessage();

        byte[] responseBytes =
                response.getBytes("UTF-8");

        exchange.sendResponseHeaders(
                500,
                responseBytes.length
        );

        exchange.getResponseBody().write(responseBytes);
        exchange.getResponseBody().close();

        e.printStackTrace();
    }
});
        // Start server
        server.start();

        System.out.println(
                "Server started at http://localhost:8080"
        );

        // =========================
        // CONSOLE PROGRAM
        // =========================

        Scanner sc = new Scanner(System.in);

        double totalUnits = 0;
        double totalBill = 0;
        double totalYearlyBill = 0;

        int applianceCount = 0;

        System.out.print(
                "How many appliances do you want to enter? "
        );

        int n = sc.nextInt();
        sc.nextLine();

        for (int i = 1; i <= n; i++) {

            System.out.println(
                    "\n--- Appliance " + i + " ---"
            );

            System.out.print(
                    "Enter Appliance Name: "
            );

            String name = sc.nextLine();

            System.out.print(
                    "Enter Power (Watts): "
            );

            double power = sc.nextDouble();

            System.out.print(
                    "Enter Usage Hours per Day: "
            );

            double hours = sc.nextDouble();

            System.out.print(
                    "Enter Electricity Rate per Unit: "
            );

            double rate = sc.nextDouble();

            sc.nextLine();

            if (power <= 0 || hours <= 0 || rate <= 0) {

                System.out.println(
                        "Please enter valid values."
                );

                sc.close();
                dbConnection.close();

                return;
            }

            double monthlyUnits =
                    (power * hours * 30) / 1000;

            double monthlyBill =
                    monthlyUnits * rate;

            double yearlyUnits =
                    monthlyUnits * 12;

            double yearlyBill =
                    monthlyBill * 12;

            totalUnits += monthlyUnits;
            totalBill += monthlyBill;
            totalYearlyBill += yearlyBill;

            applianceCount++;

            System.out.println(
                    "\n--- Electricity Analysis ---"
            );

            System.out.println(
                    "Appliance: " + name
            );

            System.out.println(
                    "Monthly Usage: " +
                    monthlyUnits + " units"
            );

            System.out.println(
                    "Estimated Monthly Bill: Rs. " +
                    monthlyBill
            );

            System.out.println(
                    "Yearly Usage: " +
                    yearlyUnits + " units"
            );

            System.out.println(
                    "Yearly Bill: Rs. " +
                    yearlyBill
            );
        }

        System.out.println(
                "\n--- Total Analysis ---"
        );

        System.out.println(
                "Total Appliances: " +
                applianceCount
        );

        System.out.println(
                "Total Monthly Usage: " +
                totalUnits + " units"
        );

        System.out.println(
                "Total Monthly Bill: Rs. " +
                totalBill
        );

        System.out.println(
                "Total Yearly Bill: Rs. " +
                totalYearlyBill
        );

        sc.close();
    }
}