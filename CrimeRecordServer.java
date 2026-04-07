import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class CrimeRecordServer {
    private static final int HTTP_PORT = 8080;
    private static final int WS_PORT = 8081;
    private static final Map<String, WebSocketClient> clients = new ConcurrentHashMap<>();
    private static final Map<String, User> users = new ConcurrentHashMap<>();
    private static final Map<String, CrimeRecord> crimes = new ConcurrentHashMap<>();
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Suspect> suspects = new ConcurrentHashMap<>();
    private static final Map<String, List<CaseUpdate>> caseUpdates = new ConcurrentHashMap<>();
    private static final Map<String, List<AuditLog>> auditLogs = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> evidenceFiles = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> notifications = new ConcurrentHashMap<>();
    private static int crimeIdCounter = 1;
    private static int suspectIdCounter = 1;
    
    // Configuration
    private static final int SESSION_TIMEOUT_MINUTES = 30;
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final String BACKUP_DIRECTORY = "backups";
    private static final long BACKUP_INTERVAL_HOURS = 24;

    static class User {
        String username, password, role, fullName;
        String email;
        String phoneNumber;
        String department;
        String badgeNumber;  // For officers
        boolean isActive;
        boolean isTwoFactorEnabled;
        String twoFactorSecret;
        int loginAttempts;
        long lastLoginAttempt;
        Set<String> permissions;
        Map<String, String> preferences;  // For UI settings, notifications, etc.

        User(String username, String password, String role, String fullName) {
            this.username = username;
            this.password = password;
            this.role = role;
            this.fullName = fullName;
            this.isActive = true;
            this.loginAttempts = 0;
            this.permissions = new HashSet<>();
            this.preferences = new HashMap<>();
            this.isTwoFactorEnabled = false;
        }
    }

    static class CrimeRecord {
        String id, caseNumber, crimeType, description, location, status, reportedBy, reportedDate, officerAssigned;
        String severity;  // Low, Medium, High, Critical
        double latitude, longitude;  // For GPS location
        List<String> evidenceFiles;  // Paths to uploaded evidence files
        List<String> witnessStatements;
        List<Suspect> suspects;
        List<CaseUpdate> updates;
        Map<String, String> metadata;  // For additional flexible data

        CrimeRecord(String id, String caseNumber, String crimeType, String description, String location,
                   String status, String reportedBy, String reportedDate, String officerAssigned, String severity) {
            this.id = id;
            this.caseNumber = caseNumber;
            this.crimeType = crimeType;
            this.description = description;
            this.location = location;
            this.status = status;
            this.reportedBy = reportedBy;
            this.reportedDate = reportedDate;
            this.officerAssigned = officerAssigned;
            this.severity = severity != null ? severity : "Medium";
            this.evidenceFiles = new ArrayList<>();
            this.witnessStatements = new ArrayList<>();
            this.suspects = new ArrayList<>();
            this.updates = new ArrayList<>();
            this.metadata = new HashMap<>();
        }
    }

    static class WebSocketClient {
        Socket socket;
        OutputStream out;
        String sessionId;
        String userId;
        WebSocketClient(Socket socket, OutputStream out, String sessionId) {
            this.socket = socket;
            this.out = out;
            this.sessionId = sessionId;
        }
    }

    static class Suspect {
        String id;
        String name;
        String description;
        String status; // Wanted, Arrested, Released, etc.
        List<String> relatedCases;
        Map<String, String> details;

        Suspect(String id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.status = "Under Investigation";
            this.relatedCases = new ArrayList<>();
            this.details = new HashMap<>();
        }
    }

    static class CaseUpdate {
        String id;
        String caseId;
        String updatedBy;
        String updateType;
        String description;
        String timestamp;
        Map<String, String> metadata;

        CaseUpdate(String id, String caseId, String updatedBy, String updateType, String description) {
            this.id = id;
            this.caseId = caseId;
            this.updatedBy = updatedBy;
            this.updateType = updateType;
            this.description = description;
            this.timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                               .format(new java.util.Date());
            this.metadata = new HashMap<>();
        }
    }

    static class AuditLog {
        String id;
        String userId;
        String action;
        String details;
        String timestamp;
        String ipAddress;

        AuditLog(String userId, String action, String details, String ipAddress) {
            this.id = UUID.randomUUID().toString();
            this.userId = userId;
            this.action = action;
            this.details = details;
            this.timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                               .format(new java.util.Date());
            this.ipAddress = ipAddress;
        }
    }

    public static void main(String[] args) {
        initializeData();
        
        // Start HTTP server for serving the HTML page
        new Thread(() -> startHttpServer()).start();
        
        // Start WebSocket server
        startWebSocketServer();
    }

    static void initializeData() {
        // Initialize users with enhanced data
        User admin = new User("admin", hashPassword("admin123"), "admin", "Administrator");
        admin.email = "admin@crimerecord.com";
        admin.department = "Administration";
        admin.permissions.addAll(Arrays.asList("MANAGE_USERS", "MANAGE_CASES", "VIEW_REPORTS", "MANAGE_SYSTEM"));
        users.put("admin", admin);

        User officer = new User("officer1", hashPassword("officer123"), "officer", "John Smith");
        officer.email = "john.smith@crimerecord.com";
        officer.department = "Criminal Investigation";
        officer.badgeNumber = "B-123";
        officer.permissions.addAll(Arrays.asList("MANAGE_CASES", "VIEW_REPORTS", "UPDATE_CASES"));
        users.put("officer1", officer);

        User clerk = new User("clerk", hashPassword("clerk123"), "clerk", "Jane Doe");
        clerk.email = "jane.doe@crimerecord.com";
        clerk.department = "Records";
        clerk.permissions.addAll(Arrays.asList("VIEW_CASES", "CREATE_REPORTS"));
        users.put("clerk", clerk);

        // Initialize sample crime records with enhanced data
        CrimeRecord crime1 = new CrimeRecord("1", "CR-2025-001", "Theft",
            "Stolen vehicle reported at downtown parking", "123 Main St",
            "Open", "John Citizen", "2025-10-15", "officer1", "High");
        crime1.latitude = 34.0522;
        crime1.longitude = -118.2437;
        crime1.updates.add(new CaseUpdate("U1", "1", "officer1", "INITIAL", "Case opened"));
        crimes.put("1", crime1);

        CrimeRecord crime2 = new CrimeRecord("2", "CR-2025-002", "Assault",
            "Physical altercation at local bar", "456 Oak Ave",
            "Under Investigation", "Mary Johnson", "2025-10-18", "officer1", "Medium");
        crime2.latitude = 34.0548;
        crime2.longitude = -118.2450;
        crime2.updates.add(new CaseUpdate("U2", "2", "officer1", "INITIAL", "Case opened"));
        crimes.put("2", crime2);

        // Initialize sample suspects
        Suspect suspect1 = new Suspect("S1", "John Doe", "Suspect in vehicle theft");
        suspect1.relatedCases.add("1");
        suspects.put("S1", suspect1);

        crimeIdCounter = 3;
        suspectIdCounter = 2;

        // Create backup directory
        new File(BACKUP_DIRECTORY).mkdirs();

        // Start periodic backup
        schedulePeriodicBackup();
    }

    static void schedulePeriodicBackup() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> performBackup(), 
            0, BACKUP_INTERVAL_HOURS, TimeUnit.HOURS);
    }

    static void performBackup() {
        try {
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                                 .format(new java.util.Date());
            String backupFile = BACKUP_DIRECTORY + "/backup_" + timestamp + ".json";
            
            // Create backup data structure
            Map<String, Object> backupData = new HashMap<>();
            backupData.put("users", users);
            backupData.put("crimes", crimes);
            backupData.put("suspects", suspects);
            backupData.put("auditLogs", auditLogs);
            
            // Write to file
            try (PrintWriter out = new PrintWriter(new FileWriter(backupFile))) {
                // Note: In a real implementation, use proper JSON serialization
                out.println(backupData.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static void logAudit(String userId, String action, String details, String ipAddress) {
        AuditLog log = new AuditLog(userId, action, details, ipAddress);
        auditLogs.computeIfAbsent(userId, k -> new ArrayList<>()).add(log);
    }

    static void addNotification(String userId, String message) {
        notifications.computeIfAbsent(userId, k -> new ArrayList<>()).add(message);
    }

    static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return password;
        }
    }

    static void startHttpServer() {
        try (ServerSocket serverSocket = new ServerSocket(HTTP_PORT)) {
            System.out.println("HTTP Server started on port " + HTTP_PORT);
            System.out.println("WebSocket Server will start on port " + WS_PORT);
            System.out.println("Open http://localhost:" + HTTP_PORT + " in your browser");
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleHttpRequest(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void handleHttpRequest(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            
            String line = in.readLine();
            if (line == null) return;
            
            // Read headers
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                // Skip headers
            }
            
            String html = getHtmlPage();
            String response = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html\r\n" +
                            "Content-Length: " + html.getBytes().length + "\r\n" +
                            "\r\n" +
                            html;
            
            out.write(response.getBytes());
            out.flush();
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void startWebSocketServer() {
        try (ServerSocket serverSocket = new ServerSocket(WS_PORT)) {
            System.out.println("WebSocket Server started on port " + WS_PORT);
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleWebSocketConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void handleWebSocketConnection(Socket socket) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            OutputStream out = socket.getOutputStream();
            
            // Read HTTP upgrade request
            String line;
            String key = null;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.startsWith("Sec-WebSocket-Key:")) {
                    key = line.substring(19).trim();
                }
            }
            
            if (key == null) {
                socket.close();
                return;
            }
            
            // Send WebSocket handshake response
            String accept = generateAcceptKey(key);
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            
            out.write(response.getBytes());
            out.flush();
            
            String clientId = UUID.randomUUID().toString();
            
            // Handle WebSocket messages
            while (!socket.isClosed()) {
                byte[] frame = readFrame(socket.getInputStream());
                if (frame == null) break;
                
                String message = new String(frame, StandardCharsets.UTF_8);
                handleMessage(message, clientId, out);
            }
        } catch (Exception e) {
            // Client disconnected
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    static String generateAcceptKey(String key) {
        try {
            String magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest((key + magic).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) return null;
        int b2 = in.read();
        if (b2 == -1) return null;

        boolean masked = (b2 & 0x80) != 0;
        int length = b2 & 0x7F;

        if (length == 126) {
            length = (in.read() << 8) | in.read();
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | in.read();
            }
        }

        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            in.read(mask);
        }

        byte[] payload = new byte[length];
        in.read(payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }

        return payload;
    }

    static void sendFrame(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        
        if (payload.length < 126) {
            out.write(payload.length);
        } else if (payload.length < 65536) {
            out.write(126);
            out.write((payload.length >> 8) & 0xFF);
            out.write(payload.length & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((payload.length >> (8 * i)) & 0xFF);
            }
        }
        
        out.write(payload);
        out.flush();
    }

    static void handleMessage(String message, String clientId, OutputStream out) {
        try {
            String[] parts = message.split("\\|", 3);
            String action = parts[0];
            String data = parts.length > 1 ? parts[1] : "";
            String extra = parts.length > 2 ? parts[2] : "";

            String response = "";
            switch (action) {
                case "LOGIN":
                    response = handleLogin(data, clientId);
                    break;
                case "LOGOUT":
                    response = handleLogout(clientId);
                    break;
                case "GET_CRIMES":
                    response = handleGetCrimes(clientId);
                    break;
                case "CREATE_CRIME":
                    response = handleCreateCrime(data, clientId);
                    break;
                case "UPDATE_CRIME":
                    response = handleUpdateCrime(data, clientId);
                    break;
                case "DELETE_CRIME":
                    response = handleDeleteCrime(data, clientId);
                    break;
                case "GET_STATS":
                    response = handleGetStats(clientId);
                    break;
                case "SEARCH_CRIMES":
                    response = handleSearchCrimes(data, extra);
                    break;
                default:
                    response = "ERROR|Unknown action";
            }

            sendFrame(out, response);
        } catch (Exception e) {
            try {
                sendFrame(out, "ERROR|" + e.getMessage());
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    static String handleSearchCrimes(String sessionId, String filtersJson) {
            // Parse filtersJson (expected to be a JSON string)
            try {
                Map<String, Object> filters = new HashMap<>();
                if (filtersJson != null && !filtersJson.isEmpty()) {
                    filters = parseJsonFilters(filtersJson);
                }
                List<CrimeRecord> filtered = new ArrayList<>();
                for (CrimeRecord crime : crimes.values()) {
                    if (!matchesFilter(crime, filters)) continue;
                    filtered.add(crime);
                }
                StringBuilder sb = new StringBuilder("SEARCH_RESULTS|");
                for (CrimeRecord crime : filtered) {
                    sb.append(crime.id).append("~")
                      .append(crime.caseNumber).append("~")
                      .append(crime.crimeType).append("~")
                      .append(crime.description).append("~")
                      .append(crime.location).append("~")
                      .append(crime.status).append("~")
                      .append(crime.reportedBy).append("~")
                      .append(crime.reportedDate).append("~")
                      .append(crime.officerAssigned).append("~")
                      .append(crime.severity).append(";");
                }
                // Remove trailing semicolon if present
                if (sb.length() > 15 && sb.charAt(sb.length()-1) == ';') sb.setLength(sb.length()-1);
                return sb.toString();
            } catch (Exception e) {
                return "SEARCH_RESULTS|"; // No results
            }
        }

        // Simple JSON parser for flat string:string filters
        static Map<String, Object> parseJsonFilters(String json) {
            Map<String, Object> map = new HashMap<>();
            try {
                json = json.trim();
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    return map;
                }
                json = json.substring(1, json.length()-1);
                
                StringBuilder currentKey = new StringBuilder();
                StringBuilder currentValue = new StringBuilder();
                boolean inValue = false;
                boolean inQuotes = false;
                
                for (int i = 0; i < json.length(); i++) {
                    char c = json.charAt(i);
                    
                    if (c == '"') {
                        inQuotes = !inQuotes;
                    } else if (c == ':' && !inQuotes) {
                        currentKey = removeQuotes(currentKey);
                        inValue = true;
                    } else if (c == ',' && !inQuotes) {
                        currentValue = removeQuotes(currentValue);
                        if (currentKey.length() > 0) {
                            map.put(currentKey.toString(), currentValue.toString());
                        }
                        currentKey = new StringBuilder();
                        currentValue = new StringBuilder();
                        inValue = false;
                    } else {
                        if (inValue) {
                            currentValue.append(c);
                        } else {
                            currentKey.append(c);
                        }
                    }
                }
                
                currentValue = removeQuotes(currentValue);
                if (currentKey.length() > 0) {
                    map.put(currentKey.toString(), currentValue.toString());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return map;
        }
        
        static StringBuilder removeQuotes(StringBuilder sb) {
            String s = sb.toString().trim();
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length()-1);
            }
            return new StringBuilder(s);
        }

        static boolean matchesFilter(CrimeRecord crime, Map<String, Object> filters) {
            if (filters == null) return true;
            String caseNumber = ((String)filters.getOrDefault("caseNumber", "")).toLowerCase();
            String crimeType = ((String)filters.getOrDefault("crimeType", "")).toLowerCase();
            String location = ((String)filters.getOrDefault("location", "")).toLowerCase();
            String status = (String)filters.getOrDefault("status", "");
            String reportedBy = ((String)filters.getOrDefault("reportedBy", "")).toLowerCase();
            String officerAssigned = ((String)filters.getOrDefault("officerAssigned", "")).toLowerCase();
            String startDate = (String)filters.getOrDefault("startDate", "");
            String endDate = (String)filters.getOrDefault("endDate", "");

            if (!caseNumber.isEmpty() && (crime.caseNumber == null || !crime.caseNumber.toLowerCase().contains(caseNumber))) return false;
            if (!crimeType.isEmpty() && (crime.crimeType == null || !crime.crimeType.toLowerCase().contains(crimeType))) return false;
            if (!location.isEmpty() && (crime.location == null || !crime.location.toLowerCase().contains(location))) return false;
            if (!status.isEmpty() && (crime.status == null || !crime.status.equals(status))) return false;
            if (!reportedBy.isEmpty() && (crime.reportedBy == null || !crime.reportedBy.toLowerCase().contains(reportedBy))) return false;
            if (!officerAssigned.isEmpty() && (crime.officerAssigned == null || !crime.officerAssigned.toLowerCase().contains(officerAssigned))) return false;
            if (!startDate.isEmpty() && (crime.reportedDate == null || crime.reportedDate.compareTo(startDate) < 0)) return false;
            if (!endDate.isEmpty() && (crime.reportedDate == null || crime.reportedDate.compareTo(endDate) > 0)) return false;
            return true;
        }

    static String handleLogin(String data, String clientId) {
        String[] creds = data.split(":");
        if (creds.length != 2) return "ERROR|Invalid credentials format";

        User user = users.get(creds[0]);
        if (user != null && user.password.equals(hashPassword(creds[1]))) {
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, creds[0]);
            return "LOGIN_SUCCESS|" + sessionId + ":" + user.role + ":" + user.fullName;
        }
        return "ERROR|Invalid username or password";
    }

    static String handleLogout(String clientId) {
        sessions.values().removeIf(v -> v.equals(clientId));
        return "LOGOUT_SUCCESS|Logged out successfully";
    }

    static String handleGetCrimes(String clientId) {
        StringBuilder sb = new StringBuilder("CRIMES|");
        for (CrimeRecord crime : crimes.values()) {
            sb.append(crime.id).append("~")
              .append(crime.caseNumber).append("~")
              .append(crime.crimeType).append("~")
              .append(crime.description).append("~")
              .append(crime.location).append("~")
              .append(crime.status).append("~")
              .append(crime.reportedBy).append("~")
              .append(crime.reportedDate).append("~")
              .append(crime.officerAssigned).append("~")
              .append(crime.severity).append(";");
        }
        return sb.toString();
    }

    static String handleCreateCrime(String data, String clientId) {
        String[] fields = data.split("~");
        if (fields.length < 7) return "ERROR|Invalid crime data";

        String id = String.valueOf(crimeIdCounter++);
        String caseNumber = "CR-2025-" + String.format("%03d", crimeIdCounter - 1);
        CrimeRecord crime = new CrimeRecord(id, caseNumber, fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields.length > 7 ? fields[7] : "Medium");
        crimes.put(id, crime);
        return "CREATE_SUCCESS|Crime record created with ID: " + id;
    }

    static String handleUpdateCrime(String data, String clientId) {
        String[] fields = data.split("~");
        if (fields.length < 7) return "ERROR|Invalid crime data";

        CrimeRecord crime = crimes.get(fields[0]);
        if (crime == null) return "ERROR|Crime record not found";

        crime.crimeType = fields[1];
        crime.description = fields[2];
        crime.location = fields[3];
        crime.status = fields[4];
        crime.reportedBy = fields[5];
        crime.reportedDate = fields[6];
        if (fields.length > 7) crime.officerAssigned = fields[7];

        return "UPDATE_SUCCESS|Crime record updated successfully";
    }

    static String handleDeleteCrime(String data, String clientId) {
        if (crimes.remove(data) != null) {
            return "DELETE_SUCCESS|Crime record deleted successfully";
        }
        return "ERROR|Crime record not found";
    }

    static String handleGetStats(String clientId) {
        int total = crimes.size();
        int open = 0, investigating = 0, closed = 0;
        for (CrimeRecord crime : crimes.values()) {
            switch (crime.status) {
                case "Open": open++; break;
                case "Under Investigation": investigating++; break;
                case "Closed": closed++; break;
            }
        }
        return "STATS|" + total + "~" + open + "~" + investigating + "~" + closed;
    }

    static String getHtmlPage() {
        return "<!DOCTYPE html><html><head><title>Crime Record Management</title></head>" +
               "<body style='font-family: Arial; text-align: center; padding: 50px;'>" +
               "<h1>Crime Record Management System</h1>" +
               "<p>The HTML client file should be opened separately.</p>" +
               "<p>WebSocket Server is running on port " + WS_PORT + "</p>" +
               "<p>Make sure to update the WebSocket URL in your HTML file to:</p>" +
               "<code>ws://localhost:" + WS_PORT + "</code>" +
               "</body></html>";
    }
}