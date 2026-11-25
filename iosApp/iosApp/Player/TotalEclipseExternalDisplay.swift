import SwiftUI
import UIKit
import ComposeApp

/// Total Eclipse: video on a connected display (USB-C/HDMI, or AirPlay screen mirroring), with the
/// controls staying on the iPad or iPhone. iOS gives the app a non-interactive scene for the
/// display; the player moves its video layer there and back without reloading the stream.
/// Nothing playing: the display shows the app's name on black.
final class TotalEclipseExternalDisplay {
    static let shared = TotalEclipseExternalDisplay()
    static let sceneConfigurationName = "TotalEclipseExternalDisplay"
    /// The display was connected or disconnected, or its size changed.
    static let didChange = Notification.Name("TotalEclipseExternalDisplayDidChange")

    private(set) var stage: StageView?
    private var window: UIWindow?
    private weak var scene: UIWindowScene?
    private weak var player: MPVPlayerViewController?

    private init() {}

    var screen: UIScreen? { scene?.screen }

    func connect(_ scene: UIWindowScene) {
        scene.screen.overscanCompensation = .none
        // The display's own mode when it's sharper than the current one (usually 4K over 1080p).
        // Never a lower one: changing the mode briefly reconnects the display.
        func pixels(_ mode: UIScreenMode?) -> CGFloat { (mode?.size.width ?? 0) * (mode?.size.height ?? 0) }
        if let preferred = scene.screen.preferredMode, pixels(preferred) > pixels(scene.screen.currentMode) {
            scene.screen.currentMode = preferred
        }
        let controller = StageViewController()
        let window = UIWindow(windowScene: scene)
        window.backgroundColor = .black
        window.rootViewController = controller
        window.isHidden = false   // never key: the keyboard and touches belong to the device
        self.scene = scene
        self.window = window
        stage = controller.stage
        controller.stage.onLayout = { NotificationCenter.default.post(name: Self.didChange, object: nil) }
        log("connected \(Int(scene.screen.bounds.width))x\(Int(scene.screen.bounds.height)) " +
            "native \(Int(scene.screen.nativeBounds.width))x\(Int(scene.screen.nativeBounds.height)) " +
            "edr \(scene.screen.potentialEDRHeadroom)")
        NotificationCenter.default.post(name: Self.didChange, object: nil)
    }

    func disconnect(_ scene: UIScene) {
        guard self.scene === scene else { return }
        log("disconnected")
        stage?.onLayout = nil
        stage = nil
        self.scene = nil
        // The player takes its video back before the window goes away.
        NotificationCenter.default.post(name: Self.didChange, object: nil)
        window?.isHidden = true
        window = nil
    }

    /// The display for [player], or nil when there is none. The latest player wins.
    func claim(for player: MPVPlayerViewController) -> StageView? {
        guard let stage else { return nil }
        self.player = player
        stage.setPlaying(true)
        return stage
    }

    func release(_ player: MPVPlayerViewController) {
        guard self.player === player || self.player == nil else { return }
        self.player = nil
        stage?.setPlaying(false)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Player/ExternalDisplay", message: message)
    }

    final class StageView: UIView {
        var onLayout: (() -> Void)?
        private let title = UILabel()

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            clipsToBounds = true
            title.attributedText = NSAttributedString(string: "TOTAL ECLIPSE", attributes: [
                .kern: 12, .font: UIFont.systemFont(ofSize: 44, weight: .light),
                .foregroundColor: UIColor(white: 1, alpha: 0.55)
            ])
            title.textAlignment = .center
            addSubview(title)
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        func setPlaying(_ playing: Bool) { title.isHidden = playing }

        override func layoutSubviews() {
            super.layoutSubviews()
            title.frame = bounds
            onLayout?()
        }
    }

    private final class StageViewController: UIViewController {
        let stage = StageView()
        override func loadView() { view = stage }
    }
}

@objc(TotalEclipseExternalDisplaySceneDelegate)
final class TotalEclipseExternalDisplaySceneDelegate: UIResponder, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        TotalEclipseExternalDisplay.shared.connect(scene)
    }

    func sceneDidDisconnect(_ scene: UIScene) {
        TotalEclipseExternalDisplay.shared.disconnect(scene)
    }
}

/// iOS 27: the app offers its content for a connected display (sits behind the app's root view).
/// Earlier versions use the scene configuration in Info.plist.
struct TotalEclipseExternalDisplayOffer: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> OfferViewController { OfferViewController() }
    func updateUIViewController(_ controller: OfferViewController, context: Context) {}

    final class OfferViewController: UIViewController {
        private var offered = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .clear
            view.isUserInteractionEnabled = false
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard #available(iOS 27.0, *), !offered else { return }
            offered = true
            let configuration = UISceneConfiguration(
                name: TotalEclipseExternalDisplay.sceneConfigurationName,
                sessionRole: .windowExternalDisplayNonInteractive
            )
            configuration.delegateClass = TotalEclipseExternalDisplaySceneDelegate.self
            registerSceneAccessory(.externalNonInteractive(sceneConfiguration: configuration))
        }
    }
}

/// What the device shows in place of the video: the title's artwork, dimmed, and where it's playing.
final class TotalEclipseRemoteBackdrop: UIView {
    private let artwork = UIImageView()
    private let shade = UIView()
    private let label = UILabel()
    private var artworkUrl: String?
    private var task: URLSessionDataTask?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        isUserInteractionEnabled = false
        artwork.contentMode = .scaleAspectFill
        artwork.clipsToBounds = true
        shade.backgroundColor = UIColor(white: 0, alpha: 0.62)
        label.textAlignment = .center
        label.numberOfLines = 0
        for view in [artwork, shade, label] as [UIView] { addSubview(view) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    deinit { task?.cancel() }

    override func layoutSubviews() {
        super.layoutSubviews()
        artwork.frame = bounds
        shade.frame = bounds
        // Above the play button in the middle of the player's controls.
        label.frame = CGRect(x: 24, y: bounds.height * 0.2, width: max(0, bounds.width - 48), height: bounds.height * 0.16)
    }

    func update(title: String?, subtitle: String?, artworkUrl: String?) {
        let text = NSMutableAttributedString(string: "Playing on the connected TV", attributes: [
            .font: UIFont.systemFont(ofSize: 15, weight: .medium), .foregroundColor: UIColor(white: 1, alpha: 0.7)
        ])
        let name = [title, subtitle].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
        if !name.isEmpty {
            text.append(NSAttributedString(string: "\n" + name, attributes: [
                .font: UIFont.systemFont(ofSize: 20, weight: .semibold), .foregroundColor: UIColor.white
            ]))
        }
        label.attributedText = text
        guard artworkUrl != self.artworkUrl else { return }
        self.artworkUrl = artworkUrl
        task?.cancel()
        artwork.image = nil
        guard let artworkUrl, let url = URL(string: artworkUrl),
              url.scheme == "https" || url.scheme == "http" || url.scheme == "file" else { return }
        task = URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let image = data.flatMap(UIImage.init(data:)) else { return }
            DispatchQueue.main.async {
                guard let self, self.artworkUrl == artworkUrl else { return }
                self.artwork.image = image
            }
        }
        task?.resume()
    }
}
