import SwiftUI

/// 呼吸球：会话状态的可视化中心。
///
/// 空闲时缓慢呼吸；在听时随麦克风电平等比放大；模型说话时换成播放电平。
struct BreathingOrb: View {

    let level: Float
    let phase: Phase
    let muted: Bool

    @State private var breathe: CGFloat = 0

    private var active: Bool {
        switch phase {
        case .listening, .speaking, .muted: return true
        default: return false
        }
    }

    private var baseScale: CGFloat {
        let clamped = CGFloat(max(0, min(1, level)))
        return 1 + clamped * 0.28
    }

    private var glowColor: Color {
        if muted { return Color.gray }
        switch phase {
        case .speaking: return Palette.coach
        case .listening: return Palette.learner
        case .connecting, .closing: return Palette.warn
        default: return Color.secondary
        }
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(glowColor.opacity(0.12))
                .frame(width: 260, height: 260)
                .scaleEffect(active ? baseScale * 1.06 : 1)

            Circle()
                .fill(
                    RadialGradient(
                        colors: [glowColor.opacity(0.55), glowColor.opacity(0.12)],
                        center: .center,
                        startRadius: 8,
                        endRadius: 110,
                    ),
                )
                .frame(width: 200, height: 200)
                .scaleEffect(active ? baseScale : 0.92 + breathe * 0.06)

            Image(systemName: muted ? "mic.slash.fill" : "waveform")
                .font(.system(size: 46, weight: .light))
                .foregroundStyle(.white)
        }
        .animation(.easeOut(duration: 0.12), value: level)
        .animation(.easeInOut(duration: 2.4), value: breathe)
        .onAppear {
            withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) {
                breathe = 1
            }
        }
    }
}
