import SwiftUI

/// 设置页：网关地址、音色、难度、翻译开关、账户信息。
struct SettingsView: View {

    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    @State private var gatewayDraft = ""
    @State private var saved = false
    @State private var confirmSignOut = false

    var body: some View {
        NavigationStack {
            Form {
                Section("服务器") {
                    TextField("https://api.yourdomain.com", text: $gatewayDraft)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .font(.system(.footnote, design: .monospaced))
                    Button {
                        model.updateGateway(gatewayDraft)
                        saved = true
                    } label: {
                        Label(saved ? "已保存" : "保存地址", systemImage: saved ? "checkmark.circle.fill" : "square.and.arrow.down")
                    }
                }.hideKeyboardOnScroll()

                Section("AI 老师") {
                    Picker("音色", selection: Binding(
                        get: { model.settings.voice },
                        set: { model.updateVoice($0) },
                    )) {
                        ForEach(Const.VOICES, id: \.self) { voice in
                            Text(voice).tag(voice)
                        }
                    }

                    Picker("难度", selection: Binding(
                        get: { model.settings.level },
                        set: { model.updateLevel($0) },
                    )) {
                        ForEach(Const.LEVELS, id: \.key) { item in
                            Text(item.key).tag(item.key)
                        }
                    }

                    Toggle("中文对照字幕", isOn: Binding(
                        get: { model.settings.autoTranslate },
                        set: { model.updateAutoTranslate($0) },
                    ))
                }

                Section("账户") {
                    LabeledContent("账号", value: model.settings.accountId.isEmpty ? "—" : model.settings.accountId)
                    LabeledContent("剩余时长", value: formatRemaining(model.settings.remainingSeconds))
                    LabeledContent(
                        "余额",
                        value: formatMoney(microUsd: model.settings.balanceMicroUsd, usdCny: model.settings.usdCny),
                    )
                    Button("刷新") {
                        Task { await model.refreshAccount() }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        confirmSignOut = true
                    } label: {
                        Text("清除本机激活信息")
                    }
                } footer: {
                    Text("清除后需要重新输入兑换码。剩余时长仍保留在服务端账号里，联系客服可找回。")
                }
            }
            .navigationTitle("设置")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear { gatewayDraft = model.settings.gatewayBase }
            .confirmationDialog("确认清除本机激活信息？", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("清除", role: .destructive) {
                    model.signOut()
                    dismiss()
                }
                Button("取消", role: .cancel) {}
            }
        }
    }
}

private extension View {
    /// Form 里滚动时收起键盘，避免挡住输入框。
    func hideKeyboardOnScroll() -> some View {
        self.scrollDismissesKeyboard(.interactively)
    }
}
