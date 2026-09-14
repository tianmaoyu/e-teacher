import SwiftUI

@main
struct SpeakMateApp: App {

    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                // 语音会话是深色也好看的中性界面，跟随系统即可
                .preferredColorScheme(nil)
        }
    }
}
