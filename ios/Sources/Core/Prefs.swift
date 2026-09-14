import Foundation
import Security

/// 本地配置。
///
/// 接入令牌放 Keychain（等价于 Android 侧的 EncryptedSharedPreferences）；
/// 其余非敏感设置放 UserDefaults。
final class Prefs {

    private let defaults = UserDefaults.standard

    private enum Key {
        static let token = "sm_access_token"
        static let accountId = "sm_account_id"
        static let gateway = "sm_gateway"
        static let voice = "sm_voice"
        static let level = "sm_level"
        static let translate = "sm_auto_translate"
        static let deviceId = "sm_device_id"
    }

    // MARK: - 设备标识

    /// 安装级 UUID，首次启动时生成，用于兑换码与设备绑定。
    var deviceId: String {
        if let existing = defaults.string(forKey: Key.deviceId), !existing.isEmpty {
            return existing
        }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: Key.deviceId)
        return fresh
    }

    /// 展示给运营侧的设备名。
    var deviceName: String {
        #if os(iOS)
        return "iPhone"
        #else
        return "Apple Device"
        #endif
    }

    // MARK: - 令牌（Keychain）

    var accessToken: String {
        get { Keychain.load(account: Key.token) ?? "" }
        set {
            if newValue.isEmpty {
                Keychain.delete(account: Key.token)
            } else {
                Keychain.save(newValue, account: Key.token)
            }
        }
    }

    func clearCredentials() {
        Keychain.delete(account: Key.token)
        defaults.removeObject(forKey: Key.accountId)
    }

    // MARK: - 普通设置

    var accountId: String {
        get { defaults.string(forKey: Key.accountId) ?? "" }
        set { defaults.set(newValue, forKey: Key.accountId) }
    }

    var gatewayBase: String {
        get {
            let raw = (defaults.string(forKey: Key.gateway) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return raw.isEmpty ? Const.DEFAULT_GATEWAY : raw
        }
        set {
            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            let stripped = trimmed.hasSuffix("/") ? String(trimmed.dropLast()) : trimmed
            defaults.set(stripped, forKey: Key.gateway)
        }
    }

    var voice: String {
        get { defaults.string(forKey: Key.voice) ?? "marin" }
        set { defaults.set(newValue, forKey: Key.voice) }
    }

    var level: String {
        get { defaults.string(forKey: Key.level) ?? Const.DEFAULT_LEVEL }
        set { defaults.set(newValue, forKey: Key.level) }
    }

    var autoTranslate: Bool {
        get {
            if defaults.object(forKey: Key.translate) == nil { return true }
            return defaults.bool(forKey: Key.translate)
        }
        set { defaults.set(newValue, forKey: Key.translate) }
    }

    var isActivated: Bool { !accessToken.isEmpty }
}

/// 极简 Keychain 封装：generic password，仅本 App 可读，随 App 卸载清除。
private enum Keychain {

    private static let service = "com.tianmaoyu.speakmate"

    static func save(_ value: String, account: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }

    static func load(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8),
              !text.isEmpty
        else { return nil }
        return text
    }

    static func delete(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
