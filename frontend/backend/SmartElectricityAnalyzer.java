import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartElectricityAnalyzer {

    private static Connection dbConnection;

    private static class Appliance {
        String name;
        double power;
        double hours;
        double rate;
        double units;
        double bill;

        Appliance(String name, double power, double hours, double rate) {
            this.name = name;
            this.power = power;
            this.hours = hours;
            this.rate = rate;
            this.units = (power * hours * 30) / 1000.0;
            this.bill = this.units * rate;
        }
    }

    public static void main(String[] args) throws Exception {

        Class.forName("org.sqlite.JDBC");

        dbConnection = DriverManager.getConnection(
                "jdbc:sqlite:/app/electricity.db"
        );

        // Create the table if it does not already exist.
        synchronized (dbConnection) {
            try (Statement st = dbConnection.createStatement()) {
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS appliances (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "appliance_name TEXT NOT NULL, " +
                    "power_watts REAL NOT NULL, " +
                    "hours_per_day REAL NOT NULL, " +
                    "units_per_month REAL NOT NULL, " +
                    "cost_per_month REAL NOT NULL)"
                );
            }
        }

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        HttpServer server = HttpServer.create(
                new java.net.InetSocketAddress(port), 0
        );

        // TEST API
        server.createContext("/test", exchange -> {
            if (!prepareRequest(exchange)) return;
            send(exchange, 200, "Java Backend Connected Successfully!");
        });

        // ANALYZE ONE APPLIANCE AND SAVE IT
        server.createContext("/analyze", exchange -> {
            if (!prepareRequest(exchange)) return;

            try {
                Map<String, String> p = parseQuery(
                        exchange.getRequestURI().getRawQuery()
                );

                String name = required(p, "name");
                double power = number(p, "power");
                double hours = number(p, "hours");
                double rate = number(p, "rate");

                validate(power, hours, rate);

                Appliance a = new Appliance(name, power, hours, rate);

                // Save the appliance whenever Analyze is clicked.
                saveAppliance(a);

                String response =
                        "Appliance: " + a.name +
                        "\nMonthly Usage: " + a.units + " units" +
                        "\nEstimated Monthly Bill: Rs. " + a.bill;

                send(exchange, 200, response);

            } catch (IllegalArgumentException e) {
                send(exchange, 400, e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "Analysis or database error: " +
                        safeMessage(e));
            }
        });

        // ANALYZE AND SAVE ALL APPLIANCES
        server.createContext("/analyzeAll", exchange -> {
            if (!prepareRequest(exchange)) return;

            try {
                Map<String, String> params = parseQuery(
                        exchange.getRequestURI().getRawQuery()
                );

                if (params.isEmpty()) {
                    throw new IllegalArgumentException(
                            "No appliance data received."
                    );
                }

                // The frontend sends its appliance list as URL-encoded JSON.
                String data = params.values().iterator().next();
                List<Appliance> appliances = parseAppliances(data);

                if (appliances.isEmpty()) {
                    throw new IllegalArgumentException(
                            "No valid appliances found."
                    );
                }

                double totalUnits = 0;
                double totalBill = 0;

                // Validate everything before inserting records.
                for (Appliance a : appliances) {
                    validate(a.power, a.hours, a.rate);
                    totalUnits += a.units;
                    totalBill += a.bill;
                }

                // Insert the batch together.
                synchronized (dbConnection) {
                    boolean previousAutoCommit =
                            dbConnection.getAutoCommit();

                    try {
                        dbConnection.setAutoCommit(false);

                        String sql =
                                "INSERT INTO appliances " +
                                "(appliance_name, power_watts, hours_per_day, " +
                                "units_per_month, cost_per_month) " +
                                "VALUES (?, ?, ?, ?, ?)";

                        try (PreparedStatement ps =
                                     dbConnection.prepareStatement(sql)) {
                            for (Appliance a : appliances) {
                                bindAppliance(ps, a);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }

                        dbConnection.commit();

                    } catch (Exception e) {
                        dbConnection.rollback();
                        throw e;
                    } finally {
                        dbConnection.setAutoCommit(previousAutoCommit);
                    }
                }

                String response =
                        "Total Appliances: " + appliances.size() +
                        "\nTotal Monthly Usage: " + totalUnits + " units" +
                        "\nTotal Monthly Bill: Rs. " + totalBill +
                        "\nTotal Yearly Bill: Rs. " + (totalBill * 12);

                send(exchange, 200, response);

            } catch (IllegalArgumentException e) {
                send(exchange, 400, e.getMessage());
            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "Database or analysis error: " +
                        safeMessage(e));
            }
        });

        // VIEW HISTORY
        server.createContext("/history", exchange -> {
            if (!prepareRequest(exchange)) return;

            try {
                StringBuilder response = new StringBuilder();

                synchronized (dbConnection) {
                    String sql =
                            "SELECT id, appliance_name, power_watts, " +
                            "hours_per_day, units_per_month, cost_per_month " +
                            "FROM appliances ORDER BY id DESC";

                    try (Statement st = dbConnection.createStatement();
                         ResultSet rs = st.executeQuery(sql)) {

                        while (rs.next()) {
                            response.append("ID: ")
                                    .append(rs.getInt("id")).append("\n");

                            response.append("Appliance: ")
                                    .append(rs.getString("appliance_name"))
                                    .append("\n");

                            response.append("Power: ")
                                    .append(rs.getDouble("power_watts"))
                                    .append(" W\n");

                            response.append("Hours/Day: ")
                                    .append(rs.getDouble("hours_per_day"))
                                    .append("\n");

                            response.append("Units/Month: ")
                                    .append(rs.getDouble("units_per_month"))
                                    .append("\n");

                            response.append("Cost/Month: Rs. ")
                                    .append(rs.getDouble("cost_per_month"))
                                    .append("\n");

                            response.append("----------------------------\n");
                        }
                    }
                }

                send(exchange, 200, response.toString());

            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "Database history error: " +
                        safeMessage(e));
            }
        });

        // CLEAR HISTORY
        server.createContext("/clearHistory", exchange -> {
            if (!prepareRequest(exchange)) return;

            try {
                synchronized (dbConnection) {
                    try (Statement st = dbConnection.createStatement()) {
                        st.executeUpdate("DELETE FROM appliances");
                    }
                }

                send(exchange, 200,
                        "All appliance history deleted successfully!");

            } catch (Exception e) {
                e.printStackTrace();
                send(exchange, 500, "Delete history error: " +
                        safeMessage(e));
            }
        });

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("Database connected successfully!");
        System.out.println("Server started on port " + port);
    }

    private static boolean prepareRequest(HttpExchange exchange)
            throws IOException {

        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Origin", "*"
        );
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Methods", "GET, POST, OPTIONS"
        );
        exchange.getResponseHeaders().set(
                "Access-Control-Allow-Headers", "Content-Type"
        );

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }

        return true;
    }

    private static void send(
            HttpExchange exchange, int status, String response)
            throws IOException {

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set(
                "Content-Type", "text/plain; charset=UTF-8"
        );

        exchange.sendResponseHeaders(status, bytes.length);

        try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query)
            throws Exception {

        Map<String, String> result = new LinkedHashMap<>();

        if (query == null || query.isEmpty()) {
            return result;
        }

        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) continue;

            String key = URLDecoder.decode(
                    pair.substring(0, index), "UTF-8"
            );
            String value = URLDecoder.decode(
                    pair.substring(index + 1), "UTF-8"
            );

            result.put(key, value);
        }

        return result;
    }

    private static String required(
            Map<String, String> values, String key) {

        String value = values.get(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required value: " + key
            );
        }

        return value.trim();
    }

    private static double number(
            Map<String, String> values, String key) {

        try {
            return Double.parseDouble(required(values, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Please enter valid numeric values."
            );
        }
    }

    private static void validate(
            double power, double hours, double rate) {

        if (!Double.isFinite(power) ||
            !Double.isFinite(hours) ||
            !Double.isFinite(rate) ||
            power <= 0 || hours <= 0 || hours > 24 || rate <= 0) {

            throw new IllegalArgumentException(
                    "Please enter valid values: power and rate must be " +
                    "positive, and hours must be between 0 and 24."
            );
        }
    }

    private static void saveAppliance(Appliance a) throws Exception {
        synchronized (dbConnection) {
            String sql =
                    "INSERT INTO appliances " +
                    "(appliance_name, power_watts, hours_per_day, " +
                    "units_per_month, cost_per_month) VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement ps =
                         dbConnection.prepareStatement(sql)) {
                bindAppliance(ps, a);
                ps.executeUpdate();
            }
        }
    }

    private static void bindAppliance(
            PreparedStatement ps, Appliance a) throws Exception {

        ps.setString(1, a.name);
        ps.setDouble(2, a.power);
        ps.setDouble(3, a.hours);
        ps.setDouble(4, a.units);
        ps.setDouble(5, a.bill);
    }

    private static List<Appliance> parseAppliances(String json) {
        List<Appliance> list = new ArrayList<>();

        // Handles the simple appliance JSON objects sent by the frontend.
        Matcher objects = Pattern.compile("\\{([^{}]*)\\}").matcher(json);

        while (objects.find()) {
            String object = objects.group(1);

            String name = jsonString(object, "name");
            double power = jsonNumber(object, "power");
            double hours = jsonNumber(object, "hours");
            double rate = jsonNumber(object, "rate");

            list.add(new Appliance(name, power, hours, rate));
        }

        return list;
    }

    private static String jsonString(String object, String key) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) +
                "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        );

        Matcher matcher = pattern.matcher(object);

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Missing appliance field: " + key
            );
        }

        return matcher.group(1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .trim();
    }

    private static double jsonNumber(String object, String key) {
        Pattern pattern = Pattern.compile(
                "\"" + Pattern.quote(key) +
                "\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)"
        );

        Matcher matcher = pattern.matcher(object);

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Missing or invalid appliance field: " + key
            );
        }

        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid numeric value for: " + key
            );
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }
}