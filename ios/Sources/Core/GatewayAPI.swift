import Foundation

/// 网关返回的业务错误，message 已经是可直接展示给用户的中文。
struct GatewayError: LocalizedError {
    let code: String
    let message: String
    let httpStatus: Int

    var errorDescription: String? { message }
}

/// 网关 REST 客户端：兑换码、账户查询、字幕翻译。
///
/// 这里不持有任何 OpenAI 凭证 —— App 只认运营方签发的 access_token。
enum GatewayAPI {

    private static let session: URLSession = {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 20
        cfg.timeoutIntervalForResource = 40
        cfg.waitsForConnectivity = true
        return URLSession(configuration: cfg)
    }()

    // MARK: - 接口

    static func redeem(base: String, code: String, deviceId: String, deviceName: String) async throws -> [String: Any] {
        let body: [String: Any] = ["code": code, "deviceId": deviceId, "deviceName": deviceName]
        return try await post(base: base, path: "/v1/redeem", body: body, token: nil)
    }

    static func me(base: String, token: String) async throws -> [String: Any] {
        try await get(base: base, path: "/v1/me", token: token)
    }

    static func translate(base: String, token: String, texts: [String]) async throws -> [String] {
        let json = try await post(
            base: base,
            path: "/v1/translate",
            body: ["texts": texts, "target": "zh-CN"],
            token: token,
        )
        return (json["translations"] as? [String]) ?? []
    }

    // MARK: - 传输

    private static func post(base: String, path: String, body: [String: Any], token: String?) async throws -> [String: Any] {
        var request = URLRequest(url: try url(base, path))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request, token: token)
    }

    private static func get(base: String, path: String, token: String?) async throws -> [String: Any] {
        var request = URLRequest(url: try url(base, path))
        request.httpMethod = "GET"
        return try await send(request, token: token)
    }

    private static func send(_ request: URLRequest, token: String?) async throws -> [String: Any] {
        var request = request
        if let token, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (200..<300).contains(status) else {
            let code = (json["error"] as? String) ?? "http_\(status)"
            let message = (json["message"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? friendly(status)
            throw GatewayError(code: code, message: message, httpStatus: status)
        }
        return json
    }

    private static func url(_ base: String, _ path: String) throws -> URL {
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        let stripped = trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
        guard let url = URL(string: stripped + path) else {
            throw GatewayError(code: "invalid_gateway", message: "服务器地址无效，请检查设置", httpStatus: 0)
        }
        return url
    }

    private static func friendly(_ status: Int) -> String {
        switch status {
        case 401: return "接入令牌无效，请重新兑换"
        case 402: return "余额不足"
        case 403: return "账号已被停用"
        case 404: return "接口不存在，请检查服务器地址"
        case 429: return "操作过于频繁，请稍后再试"
        case 502, 503: return "服务暂时不可用，请稍后重试"
        default: return "请求失败（HTTP \(status)）"
        }
    }
}
