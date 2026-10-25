import Shared
import SwiftUI

/// L'app entière est écrite en Compose Multiplatform (module Kotlin « shared ») ; SwiftUI ne fait
/// que l'héberger.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let apiUrl = Bundle.main.object(forInfoDictionaryKey: "WoolStockApiUrl") as? String
        return MainViewControllerKt.MainViewController(defaultServerUrl: apiUrl ?? "https://stock-laine.carin.family")
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Compose gère lui-même les marges : barre d'état, encoche, clavier. La caméra passe dessous.
        ComposeView()
            .ignoresSafeArea()
    }
}
