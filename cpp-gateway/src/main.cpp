#include <netdb.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cerrno>
#include <csignal>
#include <condition_variable>
#include <cstring>
#include <cstdlib>
#include <deque>
#include <iomanip>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>

namespace {
constexpr int kBacklog = 128;
constexpr std::size_t kBufferSize = 16 * 1024;
constexpr std::size_t kMaxHeaderSize = 64 * 1024;
constexpr std::size_t kTelemetryQueueSize = 8192;

class Socket {
public:
    explicit Socket(int fd = -1) : fd_(fd) {}
    ~Socket() { close(); }
    Socket(const Socket&) = delete;
    Socket& operator=(const Socket&) = delete;
    Socket(Socket&& other) noexcept : fd_(other.release()) {}
    Socket& operator=(Socket&& other) noexcept { if (this != &other) { close(); fd_ = other.release(); } return *this; }
    int get() const { return fd_; }
    int release() { int fd = fd_; fd_ = -1; return fd; }
    void close() { if (fd_ != -1) { ::close(fd_); fd_ = -1; } }
private: int fd_;
};

struct Backend { std::string host; std::string port; };
struct HttpRequest { std::string method, path, host, control_token, raw; std::size_t content_length = 0; };
std::string json_escape(const std::string& value);

enum class DecisionAction { Block, RateLimit };
struct DecisionRule {
    DecisionAction action;
    std::chrono::steady_clock::time_point expires_at;
    unsigned int requests_per_second = 0;
    std::chrono::steady_clock::time_point rate_window_started;
    unsigned int requests_in_window = 0;
    std::string reason;
};

class DecisionTable {
public:
    void apply_block(const std::string& client_ip, unsigned int ttl_seconds, std::string reason) {
        put(client_ip, DecisionAction::Block, ttl_seconds, 0, std::move(reason));
    }
    void apply_rate_limit(const std::string& client_ip, unsigned int ttl_seconds, unsigned int rps, std::string reason) {
        put(client_ip, DecisionAction::RateLimit, ttl_seconds, rps, std::move(reason));
    }
    void allow(const std::string& client_ip) { std::lock_guard<std::mutex> lock(mutex_); rules_.erase(client_ip); }
    bool permits(const std::string& client_ip, int& status, std::string& reason) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto now = std::chrono::steady_clock::now();
        const auto found = rules_.find(client_ip);
        if (found == rules_.end()) return true;
        DecisionRule& rule = found->second;
        if (now >= rule.expires_at) { rules_.erase(found); return true; }
        if (rule.action == DecisionAction::Block) { status = 403; reason = rule.reason; return false; }
        if (now - rule.rate_window_started >= std::chrono::seconds(1)) { rule.rate_window_started = now; rule.requests_in_window = 0; }
        if (++rule.requests_in_window > rule.requests_per_second) { status = 429; reason = rule.reason; return false; }
        return true;
    }
    std::string to_json() {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto now = std::chrono::steady_clock::now();
        std::ostringstream out; out << "{\"rules\":["; bool first = true;
        for (auto it = rules_.begin(); it != rules_.end();) {
            if (now >= it->second.expires_at) { it = rules_.erase(it); continue; }
            if (!first) out << ',';
            first = false;
            const auto remaining = std::chrono::duration_cast<std::chrono::seconds>(it->second.expires_at - now).count();
            out << "{\"client_ip\":\"" << it->first << "\",\"action\":\"" << (it->second.action == DecisionAction::Block ? "block" : "rate_limit") << "\",\"ttl_seconds\":" << remaining << ",\"requests_per_second\":" << it->second.requests_per_second << ",\"reason\":\"" << json_escape(it->second.reason) << "\"}";
            ++it;
        }
        return out << "]}", out.str();
    }
private:
    void put(const std::string& client_ip, DecisionAction action, unsigned int ttl_seconds, unsigned int rps, std::string reason) {
        const auto now = std::chrono::steady_clock::now();
        std::lock_guard<std::mutex> lock(mutex_);
        rules_[client_ip] = {action, now + std::chrono::seconds(std::max(1u, ttl_seconds)), rps, now, 0, std::move(reason)};
    }
    std::mutex mutex_;
    std::unordered_map<std::string, DecisionRule> rules_;
};

class BackendFailover {
public:
    explicit BackendFailover(Backend reserve) : reserve_(std::move(reserve)), enabled_(!reserve_.host.empty() && !reserve_.port.empty()) {}
    Backend select(const Backend& primary) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto found = unavailable_until_.find(key(primary));
        if (enabled_ && found != unavailable_until_.end() && std::chrono::steady_clock::now() < found->second) return reserve_;
        return primary;
    }
    bool can_fail_over(const Backend& backend) const { return enabled_ && key(backend) != key(reserve_); }
    Backend reserve() const { return reserve_; }
    void mark_failure(const Backend& backend) { std::lock_guard<std::mutex> lock(mutex_); unavailable_until_[key(backend)] = std::chrono::steady_clock::now() + std::chrono::seconds(15); }
    void mark_success(const Backend& backend) { std::lock_guard<std::mutex> lock(mutex_); unavailable_until_.erase(key(backend)); }
private:
    static std::string key(const Backend& backend) { return backend.host + ":" + backend.port; }
    Backend reserve_; bool enabled_;
    std::mutex mutex_;
    std::unordered_map<std::string, std::chrono::steady_clock::time_point> unavailable_until_;
};

class TelemetrySink {
public:
    TelemetrySink() {
        const char* host = std::getenv("GATEWAY_TELEMETRY_HOST");
        const char* port = std::getenv("GATEWAY_TELEMETRY_PORT");
        host_ = host ? host : "127.0.0.1";
        port_ = port ? port : "9090";
        worker_ = std::thread(&TelemetrySink::run, this);
    }
    ~TelemetrySink() { { std::lock_guard<std::mutex> lock(mutex_); stopping_ = true; } ready_.notify_one(); worker_.join(); }
    void publish(std::string event) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (queue_.size() >= kTelemetryQueueSize) { ++dropped_; return; }
        queue_.push_back(std::move(event)); ready_.notify_one();
    }
private:
    void run() {
        addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_DGRAM;
        addrinfo* addresses = nullptr;
        if (getaddrinfo(host_.c_str(), port_.c_str(), &hints, &addresses) != 0) return;
        Socket socket_to_analytics;
        addrinfo* target = nullptr;
        for (auto* address = addresses; address; address = address->ai_next) {
            const int fd = socket(address->ai_family, address->ai_socktype, address->ai_protocol);
            if (fd != -1) { socket_to_analytics = Socket(fd); target = address; break; }
        }
        if (!target) { freeaddrinfo(addresses); return; }
        while (true) {
            std::string event;
            { std::unique_lock<std::mutex> lock(mutex_); ready_.wait(lock, [this] { return stopping_ || !queue_.empty(); });
              if (queue_.empty() && stopping_) break;
              event = std::move(queue_.front()); queue_.pop_front(); }
            sendto(socket_to_analytics.get(), event.data(), event.size(), MSG_NOSIGNAL, target->ai_addr, target->ai_addrlen);
        }
        freeaddrinfo(addresses);
    }
    std::string host_, port_; std::deque<std::string> queue_; std::mutex mutex_; std::condition_variable ready_;
    std::thread worker_; bool stopping_ = false; std::size_t dropped_ = 0;
};

class RouteTable {
public:
    explicit RouteTable(Backend fallback) : fallback_(std::move(fallback)) {}
    Backend resolve(const std::string& host) const {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto it = routes_.find(host);
        return it == routes_.end() ? fallback_ : it->second;
    }
    void set(const std::string& host, Backend backend) {
        std::lock_guard<std::mutex> lock(mutex_);
        routes_[host] = std::move(backend);
    }
    std::string to_json() const {
        std::lock_guard<std::mutex> lock(mutex_);
        std::ostringstream out;
        out << "{\"default\":{\"host\":\"" << fallback_.host << "\",\"port\":\"" << fallback_.port << "\"},\"routes\":[";
        bool first = true;
        for (const auto& [host, backend] : routes_) {
            if (!first) out << ',';
            out << "{\"host\":\"" << host << "\",\"backend_host\":\"" << backend.host << "\",\"backend_port\":\"" << backend.port << "\"}";
            first = false;
        }
        return out << "]}" , out.str();
    }
private:
    Backend fallback_;
    mutable std::mutex mutex_;
    std::map<std::string, Backend> routes_;
};

std::string lower(std::string value) { std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); }); return value; }
std::string trim(std::string value) { const auto begin = value.find_first_not_of(" \t\r\n"); const auto end = value.find_last_not_of(" \t\r\n"); return begin == std::string::npos ? "" : value.substr(begin, end - begin + 1); }
bool constant_time_equals(const std::string& left, const std::string& right) { if (left.size() != right.size()) return false; unsigned char difference = 0; for (std::size_t index = 0; index < left.size(); ++index) difference |= static_cast<unsigned char>(left[index] ^ right[index]); return difference == 0; }
std::string json_escape(const std::string& value) { std::ostringstream out; for (unsigned char c : value) { if (c == '"' || c == '\\') out << '\\' << c; else if (c == '\n') out << "\\n"; else if (c == '\r') out << "\\r"; else if (c == '\t') out << "\\t"; else if (c < 0x20) out << "?"; else out << c; } return out.str(); }
bool all_digits(const std::string& value) { return !value.empty() && std::all_of(value.begin(), value.end(), [](unsigned char c) { return std::isdigit(c); }); }
bool all_hex(const std::string& value) { return !value.empty() && std::all_of(value.begin(), value.end(), [](unsigned char c) { return std::isxdigit(c); }); }
bool is_uuid(const std::string& value) {
    static const int hyphens[] = {8, 13, 18, 23};
    if (value.size() != 36) return false;
    for (std::size_t index = 0; index < value.size(); ++index) {
        const bool hyphen = std::find(std::begin(hyphens), std::end(hyphens), static_cast<int>(index)) != std::end(hyphens);
        if (hyphen ? value[index] != '-' : !std::isxdigit(static_cast<unsigned char>(value[index]))) return false;
    }
    return true;
}
bool is_dynamic_segment(const std::string& value) {
    if (all_digits(value) || is_uuid(value)) return true;
    if (value.rfind("o-", 0) == 0 && all_hex(value.substr(2))) return true;
    if (value.rfind("p-", 0) == 0 && all_digits(value.substr(2))) return true;
    return false;
}
std::string endpoint_of(const std::string& path) {
    const std::string clean = path.substr(0, path.find('?'));
    std::stringstream input(clean); std::string segment, output;
    while (std::getline(input, segment, '/')) {
        if (output.empty()) { output = "/"; continue; }
        if (output.size() > 1) output += '/';
        output += is_dynamic_segment(segment) ? ":id" : segment;
    }
    return output.empty() ? "/" : output;
}
std::string utc_timestamp() { const auto now = std::chrono::system_clock::now(); const auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()) % 1000; const std::time_t raw = std::chrono::system_clock::to_time_t(now); std::tm tm{}; gmtime_r(&raw, &tm); std::ostringstream out; out << std::put_time(&tm, "%Y-%m-%dT%H:%M:%S") << '.' << std::setw(3) << std::setfill('0') << millis.count() << "Z"; return out.str(); }
[[noreturn]] void fail(const std::string& message) { throw std::runtime_error(message + ": " + std::strerror(errno)); }

Socket make_listener(const char* port) {
    addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM; hints.ai_flags = AI_PASSIVE;
    addrinfo* addresses = nullptr; const int rc = getaddrinfo(nullptr, port, &hints, &addresses);
    if (rc != 0) throw std::runtime_error(std::string("getaddrinfo: ") + gai_strerror(rc));
    Socket listener;
    for (auto* address = addresses; address; address = address->ai_next) {
        const int fd = socket(address->ai_family, address->ai_socktype, address->ai_protocol); if (fd == -1) continue;
        int enabled = 1; setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
        if (bind(fd, address->ai_addr, address->ai_addrlen) == 0 && listen(fd, kBacklog) == 0) { listener = Socket(fd); break; }
        ::close(fd);
    }
    freeaddrinfo(addresses); if (listener.get() == -1) fail("cannot bind listening socket"); return listener;
}

Socket connect_to_backend(const Backend& backend) {
    addrinfo hints{}; hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM;
    addrinfo* addresses = nullptr; const int rc = getaddrinfo(backend.host.c_str(), backend.port.c_str(), &hints, &addresses);
    if (rc != 0) throw std::runtime_error(std::string("backend getaddrinfo: ") + gai_strerror(rc));
    Socket socket_to_backend;
    for (auto* address = addresses; address; address = address->ai_next) {
        const int fd = socket(address->ai_family, address->ai_socktype, address->ai_protocol); if (fd == -1) continue;
        if (connect(fd, address->ai_addr, address->ai_addrlen) == 0) { socket_to_backend = Socket(fd); break; }
        ::close(fd);
    }
    freeaddrinfo(addresses); if (socket_to_backend.get() == -1) fail("cannot connect to backend"); return socket_to_backend;
}

bool send_all(int destination, const char* data, std::size_t size) { while (size) { const ssize_t sent = send(destination, data, size, MSG_NOSIGNAL); if (sent <= 0) return false; data += sent; size -= static_cast<std::size_t>(sent); } return true; }
void reply(int client, int status, const std::string& reason, const std::string& body, const char* type = "application/json") { const std::string message = "HTTP/1.1 " + std::to_string(status) + ' ' + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + std::to_string(body.size()) + "\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n" + body; send_all(client, message.data(), message.size()); }

bool read_request_head(int client, HttpRequest& request) {
    char buffer[kBufferSize];
    while (request.raw.find("\r\n\r\n") == std::string::npos && request.raw.size() < kMaxHeaderSize) {
        const ssize_t received = recv(client, buffer, sizeof(buffer), 0); if (received <= 0) return false;
        request.raw.append(buffer, static_cast<std::size_t>(received));
    }
    const auto end = request.raw.find("\r\n\r\n"); if (end == std::string::npos) return false;
    std::istringstream lines(request.raw.substr(0, end)); std::string line;
    if (!std::getline(lines, line)) return false;
    std::istringstream start(line); start >> request.method >> request.path;
    while (std::getline(lines, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        const auto colon = line.find(':'); if (colon == std::string::npos) continue;
        const auto name = lower(trim(line.substr(0, colon))); const auto value = trim(line.substr(colon + 1));
        if (name == "host") { request.host = lower(value); const auto port = request.host.find(':'); if (port != std::string::npos) request.host.resize(port); }
        if (name == "content-length") { try { request.content_length = std::stoul(value); } catch (...) { return false; } }
        if (name == "x-gateway-control-token") request.control_token = value;
    }
    return !request.method.empty() && !request.path.empty();
}

std::string json_field(const std::string& json, const std::string& name) {
    const std::string key = "\"" + name + "\""; const auto start = json.find(key); if (start == std::string::npos) return "";
    const auto quote = json.find('"', json.find(':', start) + 1); if (quote == std::string::npos) return "";
    const auto end = json.find('"', quote + 1); return end == std::string::npos ? "" : json.substr(quote + 1, end - quote - 1);
}
unsigned int json_unsigned_field(const std::string& json, const std::string& name, unsigned int fallback = 0) {
    const std::string key = "\"" + name + "\""; const auto start = json.find(key); if (start == std::string::npos) return fallback;
    const auto colon = json.find(':', start); if (colon == std::string::npos) return fallback;
    const auto first = json.find_first_of("0123456789", colon + 1); if (first == std::string::npos) return fallback;
    const auto last = json.find_first_not_of("0123456789", first);
    try { return static_cast<unsigned int>(std::stoul(json.substr(first, last - first))); } catch (...) { return fallback; }
}
bool read_body(int client, HttpRequest& request, std::string& body) {
    const auto header_end = request.raw.find("\r\n\r\n") + 4; body = request.raw.substr(header_end);
    if (request.content_length > kMaxHeaderSize || body.size() > request.content_length) return false;
    char buffer[kBufferSize];
    while (body.size() < request.content_length) { const ssize_t bytes = recv(client, buffer, std::min(sizeof(buffer), request.content_length - body.size()), 0); if (bytes <= 0) return false; body.append(buffer, static_cast<std::size_t>(bytes)); }
    return true;
}

bool is_control_request(const HttpRequest& request) { return request.path == "/__gateway/routes" || request.path == "/__gateway/decisions" || request.path == "/__gateway/health"; }
void handle_control_request(Socket& client, HttpRequest& request, RouteTable& routes, DecisionTable& decisions, const std::string& control_token) {
    if (request.path == "/__gateway/health") { reply(client.get(), 200, "OK", "{\"status\":\"ok\"}"); return; }
    if (!control_token.empty() && !constant_time_equals(request.control_token, control_token)) { reply(client.get(), 401, "Unauthorized", "{\"error\":\"valid X-Gateway-Control-Token is required\"}"); return; }
    if (request.path == "/__gateway/decisions") {
        if (request.method == "OPTIONS") { reply(client.get(), 204, "No Content", ""); return; }
        if (request.method == "GET") { reply(client.get(), 200, "OK", decisions.to_json()); return; }
        if (request.method != "POST") { reply(client.get(), 405, "Method Not Allowed", "{\"error\":\"use GET or POST\"}"); return; }
        std::string body; if (!read_body(client.get(), request, body)) { reply(client.get(), 400, "Bad Request", "{\"error\":\"invalid body\"}"); return; }
        const auto action = lower(json_field(body, "action")); const auto client_ip = json_field(body, "client_ip");
        const auto ttl_seconds = json_unsigned_field(body, "ttl_seconds", 300); const auto rps = json_unsigned_field(body, "requests_per_second", 1); const auto reason = json_field(body, "reason");
        if (client_ip.empty() || (action != "block" && action != "rate_limit" && action != "allow")) { reply(client.get(), 400, "Bad Request", "{\"error\":\"action (block, rate_limit, allow) and client_ip are required\"}"); return; }
        if (action == "block") decisions.apply_block(client_ip, ttl_seconds, reason);
        else if (action == "rate_limit") decisions.apply_rate_limit(client_ip, ttl_seconds, std::max(1u, rps), reason);
        else decisions.allow(client_ip);
        reply(client.get(), 200, "OK", "{\"status\":\"decision applied\"}"); return;
    }
    if (request.method == "OPTIONS") { reply(client.get(), 204, "No Content", ""); return; }
    if (request.method == "GET") { reply(client.get(), 200, "OK", routes.to_json()); return; }
    if (request.method != "POST") { reply(client.get(), 405, "Method Not Allowed", "{\"error\":\"use GET or POST\"}"); return; }
    std::string body; if (!read_body(client.get(), request, body)) { reply(client.get(), 400, "Bad Request", "{\"error\":\"invalid body\"}"); return; }
    const auto host = lower(json_field(body, "host")); const auto backend_host = json_field(body, "backend_host"); const auto backend_port = json_field(body, "backend_port");
    if (host.empty() || backend_host.empty() || backend_port.empty()) { reply(client.get(), 400, "Bad Request", "{\"error\":\"host, backend_host and backend_port are required\"}"); return; }
    routes.set(host, {backend_host, backend_port}); reply(client.get(), 200, "OK", "{\"status\":\"route updated\"}");
}

struct RelayResult { std::size_t bytes = 0; int response_status = 0; };
RelayResult relay(int source, int destination, bool parse_status = false) { RelayResult result; std::string first_bytes; char buffer[kBufferSize]; while (true) { const ssize_t received = recv(source, buffer, sizeof(buffer), 0); if (received <= 0 || !send_all(destination, buffer, static_cast<std::size_t>(received))) break; result.bytes += static_cast<std::size_t>(received); if (parse_status && first_bytes.size() < 128) first_bytes.append(buffer, std::min<std::size_t>(static_cast<std::size_t>(received), 128 - first_bytes.size())); } if (parse_status) { std::istringstream status(first_bytes); std::string version; status >> version >> result.response_status; } shutdown(destination, SHUT_WR); return result; }
std::string telemetry_event(const HttpRequest& request, const Backend& backend, const std::string& client_ip, std::size_t request_bytes, const RelayResult& response, long long duration_ms, const std::string& timestamp) { std::ostringstream out; out << "{\"timestamp\":\"" << timestamp << "\",\"client_ip\":\"" << json_escape(client_ip) << "\",\"method\":\"" << json_escape(request.method) << "\",\"endpoint\":\"" << json_escape(endpoint_of(request.path)) << "\",\"host\":\"" << json_escape(request.host) << "\",\"request_bytes\":" << request_bytes << ",\"response_status\":" << response.response_status << ",\"response_bytes\":" << response.bytes << ",\"duration_ms\":" << duration_ms << ",\"backend_host\":\"" << json_escape(backend.host) << "\",\"backend_port\":\"" << json_escape(backend.port) << "\"}"; return out.str(); }
void handle_client(Socket client, RouteTable& routes, DecisionTable& decisions, BackendFailover& failover, TelemetrySink& telemetry, std::string client_ip, std::string control_token) {
    try {
        const auto started = std::chrono::steady_clock::now(); const std::string timestamp = utc_timestamp();
        HttpRequest request; if (!read_request_head(client.get(), request)) { reply(client.get(), 400, "Bad Request", "{\"error\":\"invalid HTTP request\"}"); return; }
        if (is_control_request(request)) { handle_control_request(client, request, routes, decisions, control_token); return; }
        int decision_status = 0; std::string decision_reason;
        if (!decisions.permits(client_ip, decision_status, decision_reason)) { reply(client.get(), decision_status, decision_status == 403 ? "Forbidden" : "Too Many Requests", "{\"error\":\"" + json_escape(decision_reason) + "\"}"); return; }
        const Backend primary = routes.resolve(request.host);
        Backend backend = failover.select(primary);
        Socket backend_socket;
        try {
            backend_socket = connect_to_backend(backend);
            if (backend.host == primary.host && backend.port == primary.port) failover.mark_success(primary);
        } catch (const std::exception&) {
            if (!failover.can_fail_over(backend)) throw;
            failover.mark_failure(backend);
            backend = failover.reserve();
            backend_socket = connect_to_backend(backend);
            std::cerr << "Primary backend unavailable; locally switched to reserve " << backend.host << ':' << backend.port << '\n';
        }
        std::cout << request.method << ' ' << request.path << " host=" << request.host << " -> " << backend.host << ':' << backend.port << '\n';
        if (!send_all(backend_socket.get(), request.raw.data(), request.raw.size())) return;
        RelayResult upstream_result;
        std::thread upstream([&] { upstream_result = relay(client.get(), backend_socket.get()); });
        const RelayResult downstream_result = relay(backend_socket.get(), client.get(), true); upstream.join();
        const auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
        telemetry.publish(telemetry_event(request, backend, client_ip, request.raw.size() + upstream_result.bytes, downstream_result, duration, timestamp));
    } catch (const std::exception& error) { std::cerr << "Connection error: " << error.what() << '\n'; }
}

void print_usage(const char* program) { std::cerr << "Usage: " << program << " <listen_port> <default_backend_host> <default_backend_port>\nExample: " << program << " 8080 127.0.0.1 3000\n"; }
}
int main(int argc, char* argv[]) {
    if (argc != 4) { print_usage(argv[0]); return 1; }
    std::signal(SIGPIPE, SIG_IGN);
    try {
        RouteTable routes({argv[2], argv[3]}); DecisionTable decisions;
        const char* configured_control_token = std::getenv("GATEWAY_CONTROL_TOKEN"); const std::string control_token = configured_control_token ? configured_control_token : "";
        const char* reserve_host = std::getenv("GATEWAY_RESERVE_BACKEND_HOST"); const char* reserve_port = std::getenv("GATEWAY_RESERVE_BACKEND_PORT");
        BackendFailover failover({reserve_host ? reserve_host : "", reserve_port ? reserve_port : ""}); TelemetrySink telemetry; Socket listener = make_listener(argv[1]);
        const char* telemetry_host = std::getenv("GATEWAY_TELEMETRY_HOST"); const char* telemetry_port = std::getenv("GATEWAY_TELEMETRY_PORT");
        std::cout << "Gateway listening on " << argv[1] << ". Control API: GET/POST /__gateway/routes and /__gateway/decisions" << (control_token.empty() ? " (UNPROTECTED: set GATEWAY_CONTROL_TOKEN)" : " (token protected)") << ". Telemetry UDP " << (telemetry_host ? telemetry_host : "127.0.0.1") << ':' << (telemetry_port ? telemetry_port : "9090") << '\n';
        while (true) { sockaddr_storage address{}; socklen_t size = sizeof(address); const int client_fd = accept(listener.get(), reinterpret_cast<sockaddr*>(&address), &size); if (client_fd == -1) { if (errno != EINTR) std::cerr << "accept failed: " << std::strerror(errno) << '\n'; continue; } char host[NI_MAXHOST] = "unknown"; getnameinfo(reinterpret_cast<sockaddr*>(&address), size, host, sizeof(host), nullptr, 0, NI_NUMERICHOST); std::thread(handle_client, Socket(client_fd), std::ref(routes), std::ref(decisions), std::ref(failover), std::ref(telemetry), std::string(host), control_token).detach(); }
    } catch (const std::exception& error) { std::cerr << "Fatal error: " << error.what() << '\n'; return 1; }
}
