import SwiftUI

/// 根视图：未激活走激活页，已激活走通话页；结算单与设置都以 sheet 呈现。
struct RootView: View {

    @EnvironmentObject private var model: AppModel

    @State private var showSettings = false
    @State private var showResult = false

    var body: some View {
        Group {
            if model.settings.activated {
                TalkView(onOpenSettings: { showSettings = true })
            } else {
                ActivateView(onOpenSettings: { showSettings = true })
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView().environmentObject(model)
        }
        .sheet(isPresented: $showResult) {
            if let result = model.state.result {
                ResultView(result: result, usdCny: model.settings.usdCny) {
                    showResult = false
                    model.dismissResult()
                }
            }
        }
        .onChange(of: model.state.result) { result in
            showResult = result != nil
        }
    }
}

/// 结算单：会话结束后的用量与花费明细。
struct ResultView: View {

    let result: SessionResult
    let usdCny: Double
    var onDone: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 52))
                .foregroundStyle(Palette.learner)
                .padding(.top, 28)

            Text("本次练习结束")
                .font(.title3.bold())

            VStack(spacing: 12) {
                row("对话时长", formatRemaining(result.voiceSeconds))
                row("本次花费", formatMoney(microUsd: result.costMicroUsd, usdCny: usdCny))
                row("剩余时长", formatRemaining(result.remainingSeconds))
            }
            .smCard()
            .padding(.horizontal, 22)

            if result.unmetered {
                Text("未取得服务端用量，以上为客户端估算值，最终以账单为准。")
                    .font(.caption)
                    .foregroundStyle(Palette.warn)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 30)
            }

            Spacer()

            Button(action: onDone) {
                Text("知道了")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                    .background(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(Palette.coach),
                    )
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 22)
            .padding(.bottom, 22)
        }
        .presentationDetents([.medium, .large])
        .background(Color(.systemBackground))
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
                .font(.footnote)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.system(.body, design: .rounded))
                .monospacedDigit()
        }
    }
}
