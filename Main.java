import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class Main {
    static String loggedInUser = null; // store current user's name or email

    public static void main(String[] args) throws Exception {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Static files
        server.createContext("/", new StaticHandler());

        // Dynamic handlers
        server.createContext("/signup", new SignupHandler());
        server.createContext("/login", new LoginHandler());
        server.createContext("/add-event", new AddEventHandler());
        server.createContext("/events-data", new EventsDataHandler());
        server.createContext("/register", new RegisterHandler());

        server.setExecutor(null);
        System.out.println("Server started at http://localhost:" + port);
        server.start();
    }

    // ----- Add Event Handler (matches your events table) -----
    static class AddEventHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().write("Method Not Allowed".getBytes());
                exchange.close();
                return;
            }

            // ensure driver available
            try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

            // Read form body
            InputStream is = exchange.getRequestBody();
            String body = new String(is.readAllBytes());
            String[] pairs = body.split("&");

            String event_name = "", event_date = "", venue = "", registration_fee = "", registration_closes_on = "", max_participants = "";

            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                String key = kv[0];
                String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";
                switch (key) {
                    case "event_name": event_name = value; break;
                    case "event_date": event_date = value; break;
                    case "venue": venue = value; break;
                    case "registration_fee": registration_fee = value; break;
                    case "registration_closes_on": registration_closes_on = value; break;
                    case "max_participants": max_participants = value; break;
                }
            }

            // Basic validation (reg_fee required as you requested)
            if (event_name.isEmpty() || event_date.isEmpty() || venue.isEmpty() ||
                registration_fee.isEmpty() || registration_closes_on.isEmpty() || max_participants.isEmpty()) {
                String resp = "Missing required fields";
                exchange.sendResponseHeaders(400, resp.length());
                exchange.getResponseBody().write(resp.getBytes());
                exchange.close();
                return;
            }

            // Insert into DB using proper types
            try (Connection con = DBConnection.getConnection()) {
                String sql = "INSERT INTO events (event_name, event_date, venue, reg_fee, reg_close_date, max_participants) VALUES (?,?,?,?,?,?)";
                PreparedStatement ps = con.prepareStatement(sql);

                ps.setString(1, event_name);
                // event_date and reg_close_date are DATE in DB; input is YYYY-MM-DD from <input type="date">
                ps.setDate(2, java.sql.Date.valueOf(event_date));
                ps.setString(3, venue);
                ps.setDouble(4, Double.parseDouble(registration_fee));
                ps.setDate(5, java.sql.Date.valueOf(registration_closes_on));
                ps.setInt(6, Integer.parseInt(max_participants));

                ps.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
                String response = "Error: " + e.getMessage();
                exchange.sendResponseHeaders(500, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
                return;
            }

            // Redirect back to admin page with success flag (so JS can show alert)
            exchange.getResponseHeaders().add("Location", "/admin.html?success=1");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }
    }

    // Inside Main.java



    // Handler to fetch events data as JSON for admin page (no external JSON lib)
static class EventsDataHandler implements HttpHandler {
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            exchange.sendResponseHeaders(405, 0);
            exchange.getResponseBody().write("Method Not Allowed".getBytes());
            exchange.close();
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");

        try (Connection con = DBConnection.getConnection()) {
            String sql = "SELECT id, event_name, event_date, venue, reg_fee, reg_close_date, max_participants FROM events ORDER BY event_date ASC";
            PreparedStatement ps = con.prepareStatement(sql);
            java.sql.ResultSet rs = ps.executeQuery();

            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                sb.append("{");

                sb.append("\"id\":").append(rs.getInt("id")).append(",");
                sb.append("\"event_name\":\"").append(escapeJson(rs.getString("event_name"))).append("\",");
                sb.append("\"event_date\":\"").append(rs.getDate("event_date") != null ? rs.getDate("event_date").toString() : "").append("\",");
                sb.append("\"venue\":\"").append(escapeJson(rs.getString("venue"))).append("\",");
                sb.append("\"reg_fee\":").append(rs.getDouble("reg_fee")).append(",");
                sb.append("\"reg_close_date\":\"").append(rs.getDate("reg_close_date") != null ? rs.getDate("reg_close_date").toString() : "").append("\",");
                sb.append("\"max_participants\":").append(rs.getInt("max_participants"));

                sb.append("}");
                first = false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            String response = "Error: " + e.getMessage();
            exchange.sendResponseHeaders(500, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
            return;
        }

        sb.append("]");

        byte[] out = sb.toString().getBytes();
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.getResponseBody().close();
    }

    // minimal JSON escaper for strings
    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"': b.append("\\\""); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int)c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }
}

// ----- Register Event Handler -----
static class RegisterHandler implements HttpHandler {
    public void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, 0);
            exchange.getResponseBody().write("Method Not Allowed".getBytes());
            exchange.close();
            return;
        }

        // Read form body
        InputStream is = exchange.getRequestBody();
        String body = new String(is.readAllBytes());
        String[] pairs = body.split("&");

        String eventId = null;
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length > 1 && kv[0].equals("event_id")) {
                eventId = java.net.URLDecoder.decode(kv[1], "UTF-8");
            }
        }

        if (eventId == null || eventId.isEmpty()) {
            String resp = "Missing event_id";
            exchange.sendResponseHeaders(400, resp.length());
            exchange.getResponseBody().write(resp.getBytes());
            exchange.close();
            return;
        }

        try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

        try (Connection con = DBConnection.getConnection()) {
            // Check event exists
            PreparedStatement checkEvent = con.prepareStatement("SELECT event_name, event_date FROM events WHERE id=?");
            checkEvent.setInt(1, Integer.parseInt(eventId));
            ResultSet rs = checkEvent.executeQuery();

            if (!rs.next()) {
                String resp = "Event not found";
                exchange.sendResponseHeaders(404, resp.length());
                exchange.getResponseBody().write(resp.getBytes());
                exchange.close();
                return;
            }

            String eventName = rs.getString("event_name");
            String eventDate = rs.getString("event_date");

            // Hardcoded user_name for demo (you can replace with logged-in user)
            String userName = (loggedInUser != null) ? loggedInUser : "Guest";

            // Check if already registered
            PreparedStatement checkReg = con.prepareStatement(
                "SELECT id FROM registrations WHERE event_id=? AND user_name=?");
            checkReg.setInt(1, Integer.parseInt(eventId));
            checkReg.setString(2, userName);
            ResultSet rs2 = checkReg.executeQuery();

            if (rs2.next()) {
                String resp = "Already registered";
                exchange.sendResponseHeaders(409, resp.length());
                exchange.getResponseBody().write(resp.getBytes());
                exchange.close();
                return;
            }

            // Insert registration
            PreparedStatement insert = con.prepareStatement(
                "INSERT INTO registrations (event_id, event_name, event_date, user_name) VALUES (?,?,?,?)");
            insert.setInt(1, Integer.parseInt(eventId));
            insert.setString(2, eventName);
            insert.setString(3, eventDate);
            insert.setString(4, userName);
            insert.executeUpdate();

            String resp = "Registered successfully!";
            exchange.sendResponseHeaders(200, resp.length());
            exchange.getResponseBody().write(resp.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
            String resp = "Database error: " + e.getMessage();
            exchange.sendResponseHeaders(500, resp.length());
            exchange.getResponseBody().write(resp.getBytes());
        } finally {
            exchange.close();
        }
    }
}



    // ----- Login Handler (unchanged role-based redirect to /admin.html or /events.html) -----
    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if (method.equalsIgnoreCase("GET")) {
                // Serve login page
                File file = new File(System.getProperty("user.dir") + "/public/login.html");
                if (!file.exists()) {
                    String notFound = "login.html not found in /public";
                    exchange.sendResponseHeaders(404, notFound.length());
                    exchange.getResponseBody().write(notFound.getBytes());
                    exchange.close();
                    return;
                }

                byte[] bytes = Files.readAllBytes(file.toPath());
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
                return;
            } else if (method.equalsIgnoreCase("POST")) {
                // Read form data
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes());
                String[] pairs = body.split("&");

                String email = "", password = "";

                for (String pair : pairs) {
                    String[] kv = pair.split("=", 2);
                    String key = kv[0];
                    String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";
                    if (key.equals("email")) email = value;
                    if (key.equals("password")) password = value;
                }

                try {
                    Class.forName("com.mysql.cj.jdbc.Driver");
                } catch (Exception ignored) {}

                // Verify user in DB
                try (Connection con = DBConnection.getConnection()) {
                    String sql = "SELECT name, role FROM users WHERE email=? AND password=?";
                    PreparedStatement ps = con.prepareStatement(sql);
                    ps.setString(1, email);
                    ps.setString(2, password);
                    ResultSet rs = ps.executeQuery();

                    if (rs.next()) {

                        loggedInUser = rs.getString("name");

                        String role = rs.getString("role");
                        if ("admin".equalsIgnoreCase(role)) {
                            exchange.getResponseHeaders().add("Location", "/admin.html");
                            exchange.sendResponseHeaders(302, -1);
                        } else {
                            exchange.getResponseHeaders().add("Location", "/events.html");
                            exchange.sendResponseHeaders(302, -1);
                        }
                        exchange.close();
                        return;
                    } else {
                        // invalid login -> return login with JS alert and link back
                        String response = "<script>alert('Invalid email or password'); window.location='/login';</script>";
                        byte[] out = response.getBytes();
                        exchange.getResponseHeaders().add("Content-Type", "text/html");
                        exchange.sendResponseHeaders(200, out.length);
                        exchange.getResponseBody().write(out);
                        exchange.getResponseBody().close();
                        return;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    String response = "Error: " + e.getMessage();
                    exchange.sendResponseHeaders(500, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.close();
                    return;
                }
            } else {
                exchange.sendResponseHeaders(405, 0);
                exchange.getResponseBody().write("Method Not Allowed".getBytes());
                exchange.close();
            }
        }
    }

    // ----- Static file serving (absolute path, safe even if folder name contains spaces) -----
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            File file = new File(System.getProperty("user.dir") + "/public" + path);
            if (!file.exists()) {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().write("404 Not Found".getBytes());
                exchange.getResponseBody().close();
                return;
            }

            byte[] bytes = Files.readAllBytes(file.toPath());
            exchange.getResponseHeaders().add("Content-Type", getContentType(file.getName()));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        }

        private String getContentType(String filename) {
            if (filename.endsWith(".html")) return "text/html";
            if (filename.endsWith(".css")) return "text/css";
            if (filename.endsWith(".js")) return "application/javascript";
            return "application/octet-stream";
        }
    }


    // ----- Signup Handler (GET + POST) -----
    static class SignupHandler implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();

            if (method.equalsIgnoreCase("GET")) {
                File file = new File(System.getProperty("user.dir") + "/public/signup.html");
                if (!file.exists()) {
                    String notFound = "signup.html not found in /public";
                    exchange.sendResponseHeaders(404, notFound.length());
                    exchange.getResponseBody().write(notFound.getBytes());
                    exchange.close();
                    return;
                }

                byte[] bytes = Files.readAllBytes(file.toPath());
                exchange.getResponseHeaders().add("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().close();
                return;
            }

            if (method.equalsIgnoreCase("POST")) {
                // Read submitted form data
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes());
                String[] pairs = body.split("&");

                String name="", roll_no="", email="", phone_number="", department="", year="", password="";

                for (String pair : pairs) {
                    String[] kv = pair.split("=", 2);
                    String key = kv[0];
                    String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";

                    switch (key) {
                        case "name": name = value; break;
                        case "roll_no": roll_no = value; break;
                        case "email": email = value; break;
                        case "phone_number": phone_number = value; break;
                        case "department": department = value; break;
                        case "year": year = value; break;
                        case "password": password = value; break;
                    }
                }

                try { Class.forName("com.mysql.cj.jdbc.Driver"); } catch (Exception ignored) {}

                // Insert into DB
                try (Connection con = DBConnection.getConnection()) {
                    String sql = "INSERT INTO users (name, roll_no, email, phone_number, department, year, password) VALUES (?,?,?,?,?,?,?)";
                    PreparedStatement ps = con.prepareStatement(sql);
                    ps.setString(1, name);
                    ps.setString(2, roll_no);
                    ps.setString(3, email);
                    ps.setString(4, phone_number);
                    ps.setString(5, department);
                    ps.setString(6, year);
                    ps.setString(7, password);
                    ps.executeUpdate();
                } catch (Exception e) {
                    e.printStackTrace();
                    String response = "Error: " + e.getMessage();
                    exchange.sendResponseHeaders(500, response.length());
                    exchange.getResponseBody().write(response.getBytes());
                    exchange.close();
                    return;
                }

                // Redirect to login (use /login context)
                exchange.getResponseHeaders().add("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            // Any other method -> 405
            exchange.sendResponseHeaders(405, 0);
            exchange.getResponseBody().write("Method Not Allowed".getBytes());
            exchange.close();
        }
    }
}
