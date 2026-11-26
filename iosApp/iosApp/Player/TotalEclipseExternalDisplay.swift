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
        multiviewPanes.remove(player)
        guard self.player === player || self.player == nil else {
            stage?.setPlaying(self.player != nil || multiviewPanes.count > 0)
            return
        }
        self.player = nil
        stage?.setPlaying(multiviewPanes.count > 0)
    }

    // MARK: Multiview on the TV

    /// Total Eclipse: Multiview panes share the display, each in its own place of a grid.
    private let multiviewPanes = NSHashTable<MPVPlayerViewController>.weakObjects()

    func claimMultiview(for pane: MPVPlayerViewController) -> StageView? {
        guard let stage else { return nil }
        multiviewPanes.add(pane)
        stage.setPlaying(true)
        return stage
    }

    /// The place of pane [slot] of [count] on the display: one fills it, two side by side,
    /// three or four in a 2×2 grid (the same layout as on the iPad).
    func multiviewFrame(slot: Int, count: Int) -> CGRect {
        guard let bounds = stage?.bounds, bounds.width > 1 else { return .zero }
        let gap: CGFloat = 4
        let w = bounds.width, h = bounds.height
        let rect: CGRect
        switch count {
        case ...1: rect = bounds
        case 2: rect = CGRect(x: CGFloat(slot) * w / 2, y: 0, width: w / 2, height: h)
        default: rect = CGRect(x: CGFloat(slot % 2) * w / 2, y: CGFloat(slot / 2) * h / 2, width: w / 2, height: h / 2)
        }
        return count <= 1 ? rect : rect.insetBy(dx: gap / 2, dy: gap / 2)
    }

    private func log(_ message: String) {
        InAppLogBridge.shared.info(tag: "Player/ExternalDisplay", message: message)
    }

    final class StageView: UIView {
        var onLayout: (() -> Void)?
        private let logo = UIImageView(image: UIImage(named: "TotalEclipseLogo.png"))
        private var driftTimer: Timer?
        private var idleSince = Date()
        private var driftStep = 0

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            clipsToBounds = true
            logo.contentMode = .scaleAspectFit
            addSubview(logo)
            // Kind to OLED TVs: the logo drifts, and dims after five minutes.
            driftTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in self?.drift() }
        }

        deinit { driftTimer?.invalidate() }

        private func drift() {
            guard !logo.isHidden else { return }
            driftStep += 1
            let angle = Double(driftStep) * 2.4
            UIView.animate(withDuration: 18, delay: 0, options: [.curveEaseInOut, .beginFromCurrentState]) {
                self.logo.transform = CGAffineTransform(
                    translationX: cos(angle) * self.bounds.width * 0.03,
                    y: sin(angle) * self.bounds.height * 0.05
                )
                self.logo.alpha = Date().timeIntervalSince(self.idleSince) > 300 ? 0.35 : 1
            }
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        func setPlaying(_ playing: Bool) {
            if !playing, logo.isHidden {
                idleSince = Date()
                logo.alpha = 1
            }
            logo.isHidden = playing
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            let width = bounds.width * 0.52
            logo.bounds = CGRect(x: 0, y: 0, width: width, height: width * 1004 / 2472)
            logo.center = CGPoint(x: bounds.midX, y: bounds.midY)
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

/// Browsing while the video plays on a connected display: what's playing, play/pause and stop.
/// Tapping it opens the player's controls again.
@MainActor
final class TotalEclipseRemoteBar: UIView {
    private weak var player: MPVPlayerViewController?
    private let title = UILabel()
    private let subtitle = UILabel()
    private let playButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)
    private var timer: Timer?

    init(player: MPVPlayerViewController) {
        self.player = player
        super.init(frame: .zero)
        backgroundColor = UIColor(white: 0.13, alpha: 0.97)
        layer.cornerRadius = 18
        layer.cornerCurve = .continuous
        layer.borderWidth = 1
        layer.borderColor = UIColor(white: 1, alpha: 0.12).cgColor
        title.font = .systemFont(ofSize: 15, weight: .semibold)
        title.textColor = .white
        subtitle.font = .systemFont(ofSize: 12)
        subtitle.textColor = UIColor(white: 1, alpha: 0.6)
        subtitle.text = "Playing on the connected TV"
        for (button, symbol, label) in [(closeButton, "xmark", "Stop"), (playButton, "pause.fill", "Pause")] {
            button.setImage(UIImage(systemName: symbol), for: .normal)
            button.tintColor = .white
            button.accessibilityLabel = label
        }
        playButton.addTarget(self, action: #selector(togglePlay), for: .touchUpInside)
        closeButton.addTarget(self, action: #selector(stop), for: .touchUpInside)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(open)))
        let text = UIStackView(arrangedSubviews: [title, subtitle])
        text.axis = .vertical
        text.spacing = 2
        text.isUserInteractionEnabled = false
        let row = UIStackView(arrangedSubviews: [text, playButton, closeButton])
        row.alignment = .center
        row.spacing = 4
        row.translatesAutoresizingMaskIntoConstraints = false
        addSubview(row)
        NSLayoutConstraint.activate([
            row.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 18),
            row.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            row.topAnchor.constraint(equalTo: topAnchor),
            row.bottomAnchor.constraint(equalTo: bottomAnchor),
            playButton.widthAnchor.constraint(equalToConstant: 48),
            playButton.heightAnchor.constraint(equalToConstant: 48),
            closeButton.widthAnchor.constraint(equalToConstant: 44),
            closeButton.heightAnchor.constraint(equalToConstant: 48),
            heightAnchor.constraint(equalToConstant: 60)
        ])
        playButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        title.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        text.setContentHuggingPriority(.defaultLow, for: .horizontal)
        refresh()
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func attach(to window: UIWindow) {
        translatesAutoresizingMaskIntoConstraints = false
        window.addSubview(self)
        let full = widthAnchor.constraint(equalTo: window.widthAnchor, constant: -32)
        full.priority = .defaultHigh
        NSLayoutConstraint.activate([
            full,
            widthAnchor.constraint(lessThanOrEqualToConstant: 520),
            centerXAnchor.constraint(equalTo: window.centerXAnchor),
            // Above the tab bar.
            bottomAnchor.constraint(equalTo: window.safeAreaLayoutGuide.bottomAnchor, constant: -84)
        ])
    }

    func remove() {
        timer?.invalidate()
        timer = nil
        removeFromSuperview()
    }

    private func refresh() {
        guard let player else { return }
        title.text = player.nowPlayingTitle ?? "Now playing"
        playButton.setImage(UIImage(systemName: player.isPlayerPlaying ? "pause.fill" : "play.fill"), for: .normal)
        playButton.accessibilityLabel = player.isPlayerPlaying ? "Pause" : "Play"
    }

    @objc private func togglePlay() {
        guard let player else { return }
        if player.isPlayerPlaying { player.pausePlayback() } else { player.playPlayback() }
        refresh()
    }

    @objc private func open() {
        guard let player else { return }
        _ = PlayerPictureInPictureParking.shared.restore(player: player) { _ in }
    }

    @objc private func stop() { PlayerPictureInPictureParking.shared.closeParked() }
}
