import SwiftUI
import UIKit

/// 全局配色：跟随系统深浅色，不硬编码明暗。
enum Palette {
    static let coach = Color(red: 0.36, green: 0.44, blue: 0.96)
    static let learner = Color(red: 0.13, green: 0.62, blue: 0.52)
    static let warn = Color(red: 0.90, green: 0.58, blue: 0.16)
    static let danger = Color(red: 0.86, green: 0.28, blue: 0.30)
}

extension View {
    /// 卡片外观：圆角 + 细分隔线，深浅色都清晰。
    func smCard() -> some View {
        self
            .padding(18)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color(.secondarySystemBackground)),
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color.primary.opacity(0.06), lineWidth: 1),
            )
    }
}
