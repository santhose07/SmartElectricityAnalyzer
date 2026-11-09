import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmartElectricityAnalyzer {

    private static Connection db;
    private static final Map<String, User> sessions = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    static class User {
        final int id;
        final String email;
        final String role;

        User(int id, String email, String role) {
            this.id = id;
            this.email = email;
            this.role = role;
        }
    }

    static class Appliance {
        final String name;
        final double power, hours, rate, units, bill;

        Appliance(String name, double power, double hours, double rate) {
            this.name = name;
            this.power = power;
            this.hours = hours;
            this.rate = rate;
            this.units = (power * hours * 30.0) / 1000.0;
            this.bill = units * rate;
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");

        // Render Docker uses /app as its working directory. Locally, this uses
        // electricity.db in the folder from which Java is started.
        String dbPath = System.getenv().getOrDefault("DB_PATH", "electricity.db");
        db = DriverManager.getConnection("jdbc:sqlite:" + dbPath);

        initializeDatabase();
        createAdmin();

        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        route(server, "/test", x ->
                send(x, 200, "Java Backend Connected Successfully!"));

        route(server, "/register", x -> {
            requireMethod(x, "POST");
            Map<String, String> p = params(x);
            String email = required(p, "email").toLowerCase(Locale.ROOT);
            String password = required(p, "password");

            if (!email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                send(x, 400, "Please enter a valid email address.");
                return;
            }
            if (password.length() < 8) {
                send(x, 400, "Password must contain at least 8 characters.");
                return;
            }

            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement(
                        "INSERT INTO users(email,password_hash,role) VALUES(?,?,'USER')")) {
                    ps.setString(1, email);
                    ps.setString(2, hashPassword(password));
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT).contains("unique")) {
                        send(x, 409, "This email is already registered. Please login.");
                        return;
                    }
                    throw e;
                }
            }
            send(x, 200, "Registration successful. Please login.");
        });

        route(server, "/login", x -> {
            requireMethod(x, "POST");
            Map<String, String> p = params(x);
            String email = required(p, "email").toLowerCase(Locale.ROOT);
            String password = required(p, "password");

            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT id,email,password_hash,role FROM users WHERE email=?")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !verifyPassword(password, rs.getString("password_hash"))) {
                            send(x, 401, "Invalid email or password.");
                            return;
                        }
                        User user = new User(rs.getInt("id"), rs.getString("email"), rs.getString("role"));
                        byte[] bytes = new byte[32];
                        RANDOM.nextBytes(bytes);
                        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                        sessions.put(token, user);

                        sendJson(x, 200, "{\"token\":\"" + jsonEscape(token) +
                                "\",\"email\":\"" + jsonEscape(user.email) +
                                "\",\"role\":\"" + jsonEscape(user.role) +
                                "\",\"userId\":" + user.id + "}");
                    }
                }
            }
        });

        route(server, "/logout", x -> {
            requireMethod(x, "POST");
            sessions.remove(token(x));
            send(x, 200, "Logged out successfully.");
        });

        route(server, "/analyze", x -> {
            User user = auth(x);
            if (user == null) return;
            requireMethod(x, "GET");
            Appliance a = appliance(query(x));
            save(a, user.id);
            send(x, 200, "Appliance: " + a.name +
                    "\nMonthly Usage: " + format(a.units) + " units" +
                    "\nEstimated Monthly Bill: Rs. " + format(a.bill));
        });

        route(server, "/pythonAnalyze", x -> {
            User user = auth(x);
            if (user == null) return;
            requireMethod(x, "GET");
            Appliance a = appliance(query(x));

            ProcessBuilder pb = new ProcessBuilder(
                    System.getenv().getOrDefault("PYTHON_COMMAND", "python3"),
                    "analyzer.py", a.name,
                    String.valueOf(a.power),
                    String.valueOf(a.hours),
                    String.valueOf(a.rate));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String result;
            try (InputStream in = process.getInputStream()) {
                result = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                send(x, 504, "Python analysis timed out.");
                return;
            }
            if (process.exitValue() != 0) {
                send(x, 500, "Python analysis failed: " + result);
                return;
            }
            send(x, 200, result);
        });

        route(server, "/analyzeAll", x -> {
            User user = auth(x);
            if (user == null) return;
            requireMethod(x, "GET");

            String data = query(x).get("data");
            if (data == null || data.isBlank()) {
                send(x, 400, "No appliance data received.");
                return;
            }

            Matcher matcher = Pattern.compile("\\{([^{}]*)\\}").matcher(data);
            List<Appliance> appliances = new ArrayList<>();
            while (matcher.find()) {
                String object = matcher.group(1);
                String name = jsonString(object, "name");
                double power = jsonNumber(object, "power");
                double hours = jsonNumber(object, "hours");
                double rate = jsonNumber(object, "rate");
                Appliance a = new Appliance(name, power, hours, rate);
                validate(a);
                appliances.add(a);
            }
            if (appliances.isEmpty()) {
                send(x, 400, "No valid appliances found.");
                return;
            }

            double totalUnits = 0, totalBill = 0;
            synchronized (db) {
                db.setAutoCommit(false);
                try {
                    for (Appliance a : appliances) {
                        insert(a, user.id);
                        totalUnits += a.units;
                        totalBill += a.bill;
                    }
                    db.commit();
                } catch (Exception e) {
                    db.rollback();
                    throw e;
                } finally {
                    db.setAutoCommit(true);
                }
            }

            send(x, 200, "Total Appliances: " + appliances.size() +
                    "\nTotal Monthly Usage: " + format(totalUnits) + " units" +
                    "\nTotal Monthly Bill: Rs. " + format(totalBill) +
                    "\nTotal Yearly Bill: Rs. " + format(totalBill * 12));
        });

        route(server, "/history", x -> {
            User user = auth(x);
            if (user == null) return;
            StringBuilder out = new StringBuilder();
            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement(
                        "SELECT id,appliance_name,power_watts,hours_per_day,units_per_month,cost_per_month " +
                                "FROM appliances WHERE user_id=? ORDER BY id DESC")) {
                    ps.setInt(1, user.id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.append("ID: ").append(rs.getInt("id"))
                               .append("\nAppliance: ").append(rs.getString("appliance_name"))
                               .append("\nPower: ").append(rs.getDouble("power_watts")).append(" W")
                               .append("\nHours/Day: ").append(rs.getDouble("hours_per_day"))
                               .append("\nUnits/Month: ").append(rs.getDouble("units_per_month"))
                               .append("\nCost/Month: Rs. ").append(rs.getDouble("cost_per_month"))
                               .append("\n----------------------------\n");
                        }
                    }
                }
            }
            send(x, 200, out.length() == 0 ? "No history found." : out.toString());
        });

        route(server, "/clearHistory", x -> {
            User user = auth(x);
            if (user == null) return;
            requireMethod(x, "POST");
            synchronized (db) {
                try (PreparedStatement ps = db.prepareStatement(
                        "DELETE FROM appliances WHERE user_id=?")) {
                    ps.setInt(1, user.id);
                    ps.executeUpdate();
                }
            }
            send(x, 200, "Your appliance history cleared successfully!");
        });

        route(server, "/profile", x -> {
            User user = auth(x);
            if (user == null) return;
            sendJson(x, 200, "{\"id\":" + user.id +
                    ",\"email\":\"" + jsonEscape(user.email) +
                    "\",\"role\":\"" + jsonEscape(user.role) + "\"}");
        });

        route(server, "/adminUsers", x -> {
            User user = requireAdmin(x);
            if (user == null) return;
            StringBuilder out = new StringBuilder();
            synchronized (db) {
                try (Statement s = db.createStatement();
                     ResultSet rs = s.executeQuery(
                             "SELECT id,email,role FROM users ORDER BY id")) {
                    while (rs.next()) {
                        out.append("User ID: ").append(rs.getInt("id"))
                           .append("\nEmail: ").append(rs.getString("email"))
                           .append("\nRole: ").append(rs.getString("role"))
                           .append("\n-------------------\n");
                    }
                }
            }
            send(x, 200, out.length() == 0 ? "No users found." : out.toString());
        });

        route(server, "/adminHistory", x -> {
            User user = requireAdmin(x);
            if (user == null) return;
            String sql = "SELECT a.id,a.appliance_name,a.power_watts,a.hours_per_day," +
                    "a.units_per_month,a.cost_per_month,u.email " +
                    "FROM appliances a LEFT JOIN users u ON a.user_id=u.id ORDER BY a.id DESC";
            StringBuilder out = new StringBuilder();
            synchronized (db) {
                try (Statement s = db.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                    while (rs.next()) {
                        String email = rs.getString("email");
                        out.append("Record ID: ").append(rs.getInt("id"))
                           .append("\nUser: ").append(email == null ? "Old Record" : email)
                           .append("\nAppliance: ").append(rs.getString("appliance_name"))
                           .append("\nPower: ").append(rs.getDouble("power_watts")).append(" W")
                           .append("\nHours/Day: ").append(rs.getDouble("hours_per_day"))
                           .append("\nUnits/Month: ").append(rs.getDouble("units_per_month"))
                           .append("\nCost/Month: Rs. ").append(rs.getDouble("cost_per_month"))
                           .append("\n-------------------\n");
                    }
                }
            }
            send(x, 200, out.length() == 0 ? "No appliance records found." : out.toString());
        });

        route(server, "/adminSummary", x -> {
            User user = requireAdmin(x);
            if (user == null) return;
            synchronized (db) {
                try (Statement s = db.createStatement();
                     ResultSet rs = s.executeQuery(
                             "SELECT (SELECT COUNT(*) FROM users) AS users," +
                             "(SELECT COUNT(*) FROM appliances) AS records," +
                             "(SELECT COALESCE(SUM(units_per_month),0) FROM appliances) AS units," +
                             "(SELECT COALESCE(SUM(cost_per_month),0) FROM appliances) AS cost")) {
                    rs.next();
                    sendJson(x, 200, "{\"totalUsers\":" + rs.getInt("users") +
                            ",\"totalRecords\":" + rs.getInt("records") +
                            ",\"totalUnits\":" + rs.getDouble("units") +
                            ",\"totalCost\":" + rs.getDouble("cost") + "}");
                }
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Database connected successfully!");
        System.out.println("Server started on port " + port);
    }

    private static void initializeDatabase() throws SQLException {
        synchronized (db) {
            try (Statement s = db.createStatement()) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "email TEXT UNIQUE NOT NULL," +
                        "password_hash TEXT NOT NULL," +
                        "role TEXT NOT NULL DEFAULT 'USER')");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS appliances (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "appliance_name TEXT NOT NULL," +
                        "power_watts REAL NOT NULL," +
                        "hours_per_day REAL NOT NULL," +
                        "units_per_month REAL NOT NULL," +
                        "cost_per_month REAL NOT NULL)");
                try {
                    s.executeUpdate("ALTER TABLE appliances ADD COLUMN user_id INTEGER");
                } catch (SQLException ignored) {
                    // The column already exists.
                }
            }
        }
    }

    private static void createAdmin() throws Exception {
        String email = System.getenv("ADMIN_EMAIL");
        String password = System.getenv("ADMIN_PASSWORD");
        if (email == null || password == null || email.isBlank() || password.length() < 8) {
            System.out.println("Admin environment variables not configured.");
            return;
        }
        email = email.trim().toLowerCase(Locale.ROOT);
        synchronized (db) {
            try (PreparedStatement find = db.prepareStatement(
                    "SELECT id FROM users WHERE email=?")) {
                find.setString(1, email);
                try (ResultSet rs = find.executeQuery()) {
                    String hash = hashPassword(password);
                    if (rs.next()) {
                        try (PreparedStatement update = db.prepareStatement(
                                "UPDATE users SET role='ADMIN',password_hash=? WHERE email=?")) {
                            update.setString(1, hash);
                            update.setString(2, email);
                            update.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement insert = db.prepareStatement(
                                "INSERT INTO users(email,password_hash,role) VALUES(?,?,'ADMIN')")) {
                            insert.setString(1, email);
                            insert.setString(2, hash);
                            insert.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    private static String hashPassword(String password) throws Exception {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(salt) + ":" +
                    Base64.getEncoder().encodeToString(hash);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean verifyPassword(String password, String stored) throws Exception {
        if (stored == null || !stored.contains(":")) return false;
        String[] parts = stored.split(":", 2);
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 120000, 256);
        try {
            byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } finally {
            spec.clearPassword();
        }
    }

    private static void route(HttpServer server, String path, Handler handler) {
        server.createContext(path, x -> {
            addCors(x);
            if ("OPTIONS".equalsIgnoreCase(x.getRequestMethod())) {
                x.sendResponseHeaders(204, -1);
                x.close();
                return;
            }
            try {
                handler.handle(x);
            } catch (IllegalArgumentException e) {
                send(x, 400, message(e));
            } catch (Exception e) {
                e.printStackTrace();
                send(x, 500, "Server error: " + message(e));
            }
        });
    }

    private static void addCors(HttpExchange x) {
        x.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        x.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        x.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }
    private static void requireMethod(HttpExchange x, String expected) throws IOException {
        if (!expected.equalsIgnoreCase(x.getRequestMethod())) {
            send(x, 405, "Method not allowed. Use " + expected + ".");
            throw new IllegalArgumentException("Wrong request method.");
        }
    }

    private static User auth(HttpExchange x) throws IOException {
        User user = sessions.get(token(x));
        if (user == null) {
            send(x, 401, "Please login first.");
            return null;
        }
        return user;
    }

    private static User requireAdmin(HttpExchange x) throws IOException {
        User user = auth(x);
        if (user == null) return null;
        if (!"ADMIN".equalsIgnoreCase(user.role)) {
            send(x, 403, "Admin access only.");
            return null;
        }
        return user;
    }

    private static String token(HttpExchange x) {
        String value = x.getRequestHeaders().getFirst("Authorization");
        return value != null && value.startsWith("Bearer ") ? value.substring(7).trim() : "";
    }

    private static Map<String, String> params(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> query(HttpExchange x) throws IOException {
        return parse(x.getRequestURI().getRawQuery());
    }

    private static Map<String, String> parse(String data) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        if (data == null || data.isEmpty()) return map;
        for (String pair : data.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    private static String required(Map<String, String> p, String key) {
        String value = p.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing: " + key);
        }
        return value.trim();
    }

    private static Appliance appliance(Map<String, String> p) {
        Appliance a = new Appliance(
                required(p, "name"),
                parseDouble(required(p, "power")),
                parseDouble(required(p, "hours")),
                parseDouble(required(p, "rate")));
        validate(a);
        return a;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Please enter valid numeric values.");
        }
    }

    private static void validate(Appliance a) {
        if (a.name == null || a.name.isBlank() || a.name.length() > 80 ||
                !Double.isFinite(a.power) || !Double.isFinite(a.hours) ||
                !Double.isFinite(a.rate) || a.power <= 0 ||
                a.hours <= 0 || a.hours > 24 || a.rate <= 0) {
            throw new IllegalArgumentException(
                    "Enter a valid appliance name, power, hours (up to 24), and rate.");
        }
    }

    private static void save(Appliance a, int userId) throws SQLException {
        synchronized (db) {
            insert(a, userId);
        }
    }

    private static void insert(Appliance a, int userId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO appliances(appliance_name,power_watts,hours_per_day," +
                        "units_per_month,cost_per_month,user_id) VALUES(?,?,?,?,?,?)")) {
            ps.setString(1, a.name);
            ps.setDouble(2, a.power);
            ps.setDouble(3, a.hours);
            ps.setDouble(4, a.units);
            ps.setDouble(5, a.bill);
            ps.setInt(6, userId);
            ps.executeUpdate();
        }
    }

    private static String jsonString(String object, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) +
                "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) return "";
        return matcher.group(1).replace("\\\"", "\"")
                .replace("\\\\", "\\").replace("\\n", "\n");
    }

    private static double jsonNumber(String object, String key) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(key) +
                "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing numeric field: " + key);
        }
        return parseDouble(matcher.group(1));
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static void send(HttpExchange x, int status, String message) throws IOException {
        sendWithType(x, status, message, "text/plain; charset=UTF-8");
    }

    private static void sendJson(HttpExchange x, int status, String message) throws IOException {
        sendWithType(x, status, message, "application/json; charset=UTF-8");
    }

    private static void sendWithType(HttpExchange x, int status, String message, String contentType)
            throws IOException {
        addCors(x);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", contentType);
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(bytes);
        }
    }
}