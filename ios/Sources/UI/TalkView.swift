import SwiftUI

/// 通话页：呼吸球 + 实时字幕 + 剩余时长。
struct TalkView: View {

    @EnvironmentObject private var model: AppModel

    var onOpenSettings: () -> Void

    private var state: UiState { model.state }

    var body: some View {
        VStack(spacing: 0) {
            header

            BreathingOrb(
                level: state.phase == .speaking ? state.playbackLevel : state.micLevel,
                phase: state.phase,
                muted: state.muted,
            )
            .frame(height: 280)

            Text(state.phaseText)
                .font(.headline)
                .foregroundStyle(.secondary)

            if let notice = state.notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(Palette.warn)
                    .padding(.top, 6)
                    .padding(.horizontal, 24)
                    .multilineTextAlignment(.center)
            }

            if let error = state.error {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(Palette.danger)
                    .padding(.top, 6)
                    .padding(.horizontal, 24)
                    .multilineTextAlignment(.center)
            }

            Divider().padding(.vertical, 12)

            CaptionList(captions: state.captions)
                .frame(maxHeight: .infinity)

            controls
        }
        .background(Color(.systemBackground))
        .onDisappear {
            if state.inSession { model.endSession() }
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("剩余")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(formatRemaining(state.remainingSeconds))
                    .font(.system(size: 20, weight: .semibold, design: .rounded))
                    .foregroundStyle(state.lowBalance ? Palette.danger : .primary)
                    .monospacedDigit()
            }

            Spacer()

            if state.inSession {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("本次花费")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(formatMoney(microUsd: state.sessionCostMicroUsd, usdCny: model.settings.usdCny))
                        .font(.system(size: 15, weight: .medium, design: .rounded))
                        .monospacedDigit()
                }
            }

            Button(action: onOpenSettings) {
                Image(systemName: "gearshape")
                    .font(.system(size: 18))
                    .padding(8)
            }
            .disabled(state.inSession)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    private var controls: some View {
        HStack(spacing: 14) {
            if state.inSession {
                Button {
                    model.toggleMute()
                } label: {
                    Label(state.muted ? "取消静音" : "静音", systemImage: state.muted ? "mic.slash.fill" : "mic.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(state.muted ? Palette.warn.opacity(0.85) : Color(.tertiarySystemBackground)),
                        )
                        .foregroundStyle(state.muted ? .white : .primary)
                }

                Button {
                    model.endSession()
                } label: {
                    Label("结束", systemImage: "phone.down.fill")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(Palette.danger),
                        )
                        .foregroundStyle(.white)
                }
            } else {
                Button {
                    model.startSession()
                } label: {
                    Label("开始对话", systemImage: "phone.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(
                            RoundedRectangle(cornerRadius: 16, style: .continuous)
                                .fill(Palette.coach),
                        )
                        .foregroundStyle(.white)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 10)
    }
}
