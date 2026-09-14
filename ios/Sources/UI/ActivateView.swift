import SwiftUI

/// 激活页：输入运营方发放的兑换码即可开聊，无需注册账号。
struct ActivateView: View {

    @EnvironmentObject private var model: AppModel

    @State private var code = ""
    @State private var busy = false
    @State private var message: String?
    @State private var isError = false

    var onOpenSettings: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                VStack(spacing: 8) {
                    Image(systemName: "waveform.circle.fill")
                        .font(.system(size: 62))
                        .foregroundStyle(Palette.coach)
                    Text("SpeakMate")
                        .font(.largeTitle.bold())
                    Text("和 AI 老师随时练口语")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 40)

                VStack(alignment: .leading, spacing: 12) {
                    Text("兑换码")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)

                    TextField("SM-XXXX-XXXX", text: $code)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                        .padding(12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color(.tertiarySystemBackground)),
                        )

                    Button {
                        Task { await submit() }
                    } label: {
                        HStack {
                            if busy { ProgressView().tint(.white) }
                            Text(busy ? "激活中…" : "激活并开始")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(code.trimmingCharacters(in: .whitespaces).isEmpty ? Color.gray : Palette.coach),
                        )
                        .foregroundStyle(.white)
                    }
                    .disabled(busy || code.trimmingCharacters(in: .whitespaces).isEmpty)

                    if let message {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(isError ? Palette.danger : Palette.learner)
                    }
                }
                .smCard()

                VStack(alignment: .leading, spacing: 8) {
                    InfoRow(icon: "mic.fill", text: "实时全双工语音，像打电话一样自然")
                    InfoRow(icon: "text.bubble.fill", text: "双语字幕，说错也能看得见")
                    InfoRow(icon: "clock.fill", text: "按使用时长计费，余额实时可见")
                }
                .smCard()

                Button(action: onOpenSettings) {
                    Label("服务器与音色设置", systemImage: "gearshape")
                        .font(.footnote)
                }
                .padding(.bottom, 24)
            }
            .padding(.horizontal, 22)
        }
        .background(Color(.systemBackground))
    }

    private func submit() async {
        busy = true
        let result = await model.redeem(code: code)
        busy = false
        switch result {
        case .success(let text):
            isError = false
            message = text
            code = ""
        case .failure(let error):
            isError = true
            message = error.localizedDescription
        }
    }
}

private struct InfoRow: View {
    let icon: String
    let text: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(Palette.coach)
                .frame(width: 20)
            Text(text)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }
}
