import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Compose 自己有 insets 处理（CMP 1.12 的 UIKitWindowInsetsManager 把
        // safeAreaInsets 接进 WindowInsets），所以这里让 SwiftUI 全铺，
        // 由 iosMain/MainViewController.kt 里的 windowInsetsPadding(safeDrawing) 留边 ——
        // 与 Android MainActivity 同一份逻辑。
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
