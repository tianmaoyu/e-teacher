import SwiftUI

/// 双语字幕列表：你说的话在右，老师说的在左，中文对照附在英文下方。
struct CaptionList: View {

    let captions: [Caption]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(captions) { caption in
                        CaptionBubble(caption: caption)
                            .id(caption.id)
                    }
                }
                .padding(.vertical, 4)
            }
            .onChange(of: captions.last?.id) { target in
                guard let target else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(target, anchor: .bottom)
                }
            }
        }
    }
}

private struct CaptionBubble: View {

    let caption: Caption

    private var isLearner: Bool { caption.speaker == .learner }

    private var tint: Color { isLearner ? Palette.learner : Palette.coach }

    var body: some View {
        HStack {
            if isLearner { Spacer(minLength: 36) }

            VStack(alignment: .leading, spacing: 4) {
                Text(isLearner ? "你说" : "Emma")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(tint)

                Text(caption.english)
                    .font(.system(size: 16))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                if let chinese = caption.chinese, !chinese.isEmpty {
                    Text(chinese)
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(tint.opacity(0.10)),
            )

            if !isLearner { Spacer(minLength: 36) }
        }
    }
}
