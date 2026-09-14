import Foundation

/// 剩余时长：`1:05` / `1:02:03`
func formatRemaining(_ seconds: Int) -> String {
    let safe = max(0, seconds)
    let m = safe / 60
    let s = safe % 60
    if m >= 60 {
        return String(format: "%d:%02d:%02d", m / 60, m % 60, s)
    }
    return String(format: "%d:%02d", m, s)
}

/// 微美元 → 展示货币。汇率由服务端下发，调价不用发版。
func formatMoney(microUsd: Int64, usdCny: Double) -> String {
    let usd = Double(microUsd) / 1_000_000.0
    return String(format: "¥%.2f", usd * usdCny)
}
