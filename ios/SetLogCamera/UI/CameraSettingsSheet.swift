import AVKit
import SwiftUI
import UniformTypeIdentifiers
import UIKit

private let sharedThumbnailGenerator = VideoThumbnailGenerator()

// MARK: - Camera settings

struct CameraControlSettingsSheet: View {
    @ObservedObject var viewModel: CameraViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var inputSettings: InputSettings
    @State private var timestampSettings: TimestampOverlaySettings

    init(viewModel: CameraViewModel) {
        self.viewModel = viewModel
        _inputSettings = State(initialValue: viewModel.inputSettings)
        _timestampSettings = State(initialValue: viewModel.timestampSettings)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(String(localized: "settings.record-control"), selection: $inputSettings.recordControl) {
                        ForEach(CaptureControl.allCases) { control in
                            Label(controlTitle(control), systemImage: controlSymbol(control))
                                .tag(control)
                        }
                    }

                    Picker(String(localized: "settings.double-press"), selection: $inputSettings.doublePressAction) {
                        ForEach(ShortcutAction.allCases) { action in
                            Text(actionTitle(action)).tag(action)
                        }
                    }

                    Picker(String(localized: "settings.triple-press"), selection: $inputSettings.triplePressAction) {
                        ForEach(ShortcutAction.allCases) { action in
                            Text(actionTitle(action)).tag(action)
                        }
                    }
                } header: {
                    Text(String(localized: "settings.hardware.header"))
                } footer: {
                    Text(String(localized: "settings.hardware.footer.ios"))
                }

                Section {
                    TimestampOverlayEditor(settings: $timestampSettings)
                } header: {
                    Text(String(localized: "timestamp.header"))
                } footer: {
                    Text(String(localized: "timestamp.footer"))
                }
            }
            .navigationTitle(String(localized: "camera.settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "common.cancel")) {
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "common.done")) {
                        viewModel.saveInputSettings(inputSettings)
                        viewModel.saveTimestampDefaults(timestampSettings)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .presentationDetents([.large])
    }

    private func controlTitle(_ control: CaptureControl) -> String {
        String(localized: control == .secondary ? "control.secondary" : "control.primary")
    }

    private func controlSymbol(_ control: CaptureControl) -> String {
        control == .secondary ? "plus.circle.fill" : "minus.circle.fill"
    }

    private func actionTitle(_ action: ShortcutAction) -> String {
        switch action {
        case .finish:
            String(localized: "shortcut.action.finish")
        case .openGallery:
            String(localized: "shortcut.action.gallery")
        case .none:
            String(localized: "shortcut.action.none")
        }
    }
}

struct TimestampOverlayEditor: View {
    @Binding var settings: TimestampOverlaySettings

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Toggle(String(localized: "timestamp.enabled"), isOn: enabledBinding)

            TimestampOverlayPreview(settings: $settings, interactive: settings.enabled)
                .frame(height: 290)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .stroke(.secondary.opacity(0.28), lineWidth: 1)
                )
                .opacity(settings.enabled ? 1 : 0.45)

            Text(String(localized: "timestamp.drag-hint"))
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack {
                Label(String(localized: "timestamp.size"), systemImage: "textformat.size")
                Slider(
                    value: Binding(
                        get: { settings.scale },
                        set: { settings.scale = $0 }
                    ),
                    in: 0.60...1.80
                )
                Text(String(format: "%.0f%%", settings.scale * 100))
                    .font(.caption.monospacedDigit())
                    .frame(width: 48, alignment: .trailing)
            }
            .disabled(!settings.enabled)

            Picker(String(localized: "timestamp.style"), selection: $settings.style) {
                Text(String(localized: "timestamp.style.clean")).tag(TimestampStyle.clean)
                Text(String(localized: "timestamp.style.boxed")).tag(TimestampStyle.boxed)
                Text(String(localized: "timestamp.style.monospaced")).tag(TimestampStyle.monospaced)
            }
            .pickerStyle(.segmented)
            .disabled(!settings.enabled)

            HStack(spacing: 8) {
                PositionPresetButton(
                    title: String(localized: "timestamp.position.top"),
                    symbol: "rectangle.topthird.inset.filled",
                    action: { setPosition(x: 0.5, y: 0.14) }
                )
                PositionPresetButton(
                    title: String(localized: "timestamp.position.center"),
                    symbol: "rectangle.center.inset.filled",
                    action: { setPosition(x: 0.5, y: 0.5) }
                )
                PositionPresetButton(
                    title: String(localized: "timestamp.position.bottom"),
                    symbol: "rectangle.bottomthird.inset.filled",
                    action: { setPosition(x: 0.5, y: 0.84) }
                )
            }
            .disabled(!settings.enabled)
        }
    }

    private var enabledBinding: Binding<Bool> {
        Binding(
            get: { settings.enabled },
            set: { settings.enabled = $0 }
        )
    }

    private func setPosition(x: Double, y: Double) {
        settings.x = x
        settings.y = y
    }
}

struct TimestampOverlayPreview: View {
    @Binding var settings: TimestampOverlaySettings
    let interactive: Bool
    @State private var dragOrigin: TimestampOverlaySettings?
    @State private var magnifyOrigin: TimestampOverlaySettings?

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                LinearGradient(
                    colors: [.black.opacity(0.92), .gray.opacity(0.42)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
                GridGuide()
                    .stroke(.white.opacity(0.12), lineWidth: 1)

                if settings.enabled {
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        Text(DateFormatters.overlay.string(from: context.date))
                            .font(timestampFont)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .padding(.horizontal, settings.style == .boxed ? 10 : 3)
                            .padding(.vertical, settings.style == .boxed ? 6 : 2)
                            .background(
                                settings.style == .boxed ? Color.black.opacity(0.68) : .clear,
                                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                            )
                            .shadow(color: .black.opacity(0.72), radius: settings.style == .clean ? 3 : 0)
                            .scaleEffect(settings.scale)
                            .position(
                                x: proxy.size.width * settings.sanitized.x,
                                y: proxy.size.height * settings.sanitized.y
                            )
                            .gesture(dragGesture(in: proxy.size))
                            .simultaneousGesture(magnifyGesture)
                            .accessibilityLabel(String(localized: "timestamp.preview"))
                    }
                } else {
                    Label(String(localized: "timestamp.off"), systemImage: "eye.slash")
                        .font(.headline)
                        .foregroundStyle(.white.opacity(0.65))
                }
            }
            .contentShape(Rectangle())
        }
    }

    private var timestampFont: Font {
        settings.style == .monospaced
            ? .system(.body, design: .monospaced, weight: .semibold)
            : .system(.body, design: .rounded, weight: .semibold)
    }

    private func dragGesture(in size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard interactive else { return }
                if dragOrigin == nil {
                    dragOrigin = settings
                }
                guard let origin = dragOrigin else { return }
                settings.x = origin.x + value.translation.width / max(size.width, 1)
                settings.y = origin.y + value.translation.height / max(size.height, 1)
                settings = settings.sanitized
            }
            .onEnded { _ in
                dragOrigin = nil
                settings = settings.sanitized
            }
    }

    private var magnifyGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                guard interactive else { return }
                if magnifyOrigin == nil {
                    magnifyOrigin = settings
                }
                guard let origin = magnifyOrigin else { return }
                settings.scale = origin.scale * value.magnification
                settings = settings.sanitized
            }
            .onEnded { _ in
                magnifyOrigin = nil
                settings = settings.sanitized
            }
    }
}

private struct PositionPresetButton: View {
    let title: String
    let symbol: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                Text(title)
                    .font(.caption2)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.bordered)
    }
}

private struct GridGuide: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        for fraction in [1.0 / 3.0, 2.0 / 3.0] {
            path.move(to: CGPoint(x: rect.width * fraction, y: 0))
            path.addLine(to: CGPoint(x: rect.width * fraction, y: rect.height))
            path.move(to: CGPoint(x: 0, y: rect.height * fraction))
            path.addLine(to: CGPoint(x: rect.width, y: rect.height * fraction))
        }
        return path
    }
}

// MARK: - Gallery

struct GalleryScreen: View {
    @ObservedObject var viewModel: CameraViewModel
    @State private var importPickerPresented = false

    private var resumableSessions: [SetLogSession] {
        viewModel.sessions.filter(\.isResumable)
    }

    private var completedSessions: [SetLogSession] {
        viewModel.sessions.filter { !$0.isResumable }
    }

    var body: some View {
        NavigationStack {
            Group {
                if viewModel.sessions.isEmpty {
                    ContentUnavailableView {
                        Label(String(localized: "gallery.empty.title"), systemImage: "video.slash")
                    } description: {
                        Text(String(localized: "gallery.empty.message"))
                    } actions: {
                        Button {
                            importPickerPresented = true
                        } label: {
                            Label(String(localized: "gallery.import"), systemImage: "square.and.arrow.down")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 22) {
                            if !resumableSessions.isEmpty {
                                gallerySection(
                                    title: String(localized: "gallery.unfinished"),
                                    subtitle: String(localized: "gallery.unfinished.subtitle"),
                                    sessions: resumableSessions,
                                    isDraftSection: true
                                )
                            }
                            if !completedSessions.isEmpty {
                                gallerySection(
                                    title: String(localized: "gallery.saved"),
                                    subtitle: nil,
                                    sessions: completedSessions,
                                    isDraftSection: false
                                )
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 18)
                    }
                }
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle(String(localized: "gallery.title"))
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        importPickerPresented = true
                    } label: {
                        if viewModel.importInProgress {
                            ProgressView()
                        } else {
                            Label(String(localized: "gallery.import"), systemImage: "square.and.arrow.down")
                        }
                    }
                    .disabled(viewModel.importInProgress)

                    Button(action: viewModel.returnToCamera) {
                        Label(String(localized: "gallery.camera"), systemImage: "camera.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.primary)
                    .foregroundStyle(Color(uiColor: .systemBackground))
                }
            }
            .sheet(item: $viewModel.selectedSession) { session in
                SessionDetailSheet(viewModel: viewModel, session: session)
            }
            .fileImporter(
                isPresented: $importPickerPresented,
                allowedContentTypes: [.movie],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        viewModel.importVideo(url)
                    }
                case .failure(let error):
                    viewModel.alertMessage = error.localizedDescription
                }
            }
        }
    }

    @ViewBuilder
    private func gallerySection(
        title: String,
        subtitle: String?,
        sessions: [SetLogSession],
        isDraftSection: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.title3.bold())
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            ForEach(sessions) { session in
                SessionCard(
                    viewModel: viewModel,
                    session: session,
                    emphasizesDraft: isDraftSection
                )
            }
        }
    }
}

private struct SessionCard: View {
    @ObservedObject var viewModel: CameraViewModel
    let session: SetLogSession
    let emphasizesDraft: Bool

    var body: some View {
        Button {
            viewModel.selectSession(session)
        } label: {
            HStack(spacing: 13) {
                SessionThumbnail(url: viewModel.previewURL(for: session))
                    .frame(width: 116, height: 82)
                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 7) {
                        Text(session.title)
                            .font(.headline)
                            .lineLimit(1)
                        Spacer(minLength: 4)
                        statusBadge
                    }
                    Text(DateFormatters.display.string(from: session.createdAt))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    HStack(spacing: 12) {
                        Label(formatDuration(session.totalDurationSeconds), systemImage: "clock")
                        Label("\(session.markers.count)", systemImage: "bookmark.fill")
                        if session.isImported {
                            Label(String(localized: "gallery.imported"), systemImage: "square.and.arrow.down")
                        }
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    if !session.caption.isEmpty {
                        Text(session.caption)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
            .padding(12)
            .background(
                emphasizesDraft ? Color.orange.opacity(0.12) : Color(uiColor: .secondarySystemGroupedBackground),
                in: RoundedRectangle(cornerRadius: 19, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 19, style: .continuous)
                    .stroke(emphasizesDraft ? Color.orange.opacity(0.45) : .clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityHint(String(localized: "gallery.open-details"))
    }

    @ViewBuilder
    private var statusBadge: some View {
        switch session.status {
        case .draft:
            Label(String(localized: "status.draft"), systemImage: "pause.fill")
                .foregroundStyle(.orange)
                .badgeStyle()
        case .failed:
            Label(String(localized: "status.failed"), systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
                .badgeStyle()
        case .exporting:
            Label(String(localized: "status.saving"), systemImage: "arrow.triangle.2.circlepath")
                .foregroundStyle(.blue)
                .badgeStyle()
        case .ready:
            Label(String(localized: "status.saved"), systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .badgeStyle()
        }
    }
}

private struct SessionThumbnail: View {
    let url: URL?
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Rectangle().fill(.black)
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "video.fill")
                    .font(.title2)
                    .foregroundStyle(.white.opacity(0.65))
            }
        }
        .clipped()
        .task(id: url) {
            guard let url else {
                image = nil
                return
            }
            image = await sharedThumbnailGenerator.image(for: url)
        }
    }
}

private struct SessionDetailSheet: View {
    @ObservedObject var viewModel: CameraViewModel
    let session: SetLogSession
    @Environment(\.dismiss) private var dismiss
    @State private var title: String
    @State private var caption: String
    @State private var timestampOverlay: TimestampOverlaySettings
    @State private var player: AVPlayer?
    @State private var deleteConfirmation = false

    init(viewModel: CameraViewModel, session: SetLogSession) {
        self.viewModel = viewModel
        self.session = session
        _title = State(initialValue: session.title)
        _caption = State(initialValue: session.caption)
        _timestampOverlay = State(initialValue: session.effectiveTimestampOverlay)
    }

    private var isRebuilding: Bool {
        viewModel.rebuildingSessionID == session.id
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ZStack {
                        RoundedRectangle(cornerRadius: 16).fill(.black)
                        if let player {
                            VideoPlayer(player: player)
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                        } else {
                            ProgressView().tint(.white)
                        }
                    }
                    .aspectRatio(16 / 10, contentMode: .fit)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }

                Section(String(localized: "details.info")) {
                    TextField(String(localized: "details.title"), text: $title)
                        .textInputAutocapitalization(.sentences)
                    TextField(
                        String(localized: "details.caption"),
                        text: $caption,
                        axis: .vertical
                    )
                    .lineLimit(3...8)
                    LabeledContent(String(localized: "details.duration")) {
                        Text(formatDuration(session.totalDurationSeconds))
                            .monospacedDigit()
                    }
                    LabeledContent(String(localized: "details.clips")) {
                        Text("\(session.segments.count)")
                    }
                    LabeledContent(String(localized: "details.created")) {
                        Text(DateFormatters.display.string(from: session.createdAt))
                            .multilineTextAlignment(.trailing)
                    }
                    if session.isImported {
                        LabeledContent(String(localized: "gallery.imported")) {
                            Image(systemName: "checkmark.circle.fill")
                                .foregroundStyle(.green)
                        }
                    }
                }

                Section {
                    TimestampOverlayEditor(settings: $timestampOverlay)
                } header: {
                    Text(String(localized: "timestamp.header"))
                } footer: {
                    Text(String(localized: "timestamp.gallery.footer"))
                }

                if let error = session.errorMessage, !error.isEmpty {
                    Section(String(localized: "details.problem")) {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                    }
                }

                Section(String(localized: "details.press-times")) {
                    if session.markers.isEmpty {
                        Text(String(localized: "details.no-markers"))
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(session.markers.sorted(by: { $0.ordinal < $1.ordinal })) { marker in
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(
                                        String(
                                            format: String(localized: "details.press-number.format"),
                                            marker.ordinal
                                        )
                                    )
                                    .font(.subheadline.bold())
                                    Text(DateFormatters.display.string(from: marker.pressedAt))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text("+\(formatDuration(marker.timelineOffsetSeconds))")
                                    .font(.caption.monospacedDigit().bold())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                Section {
                    Button {
                        saveEdits()
                    } label: {
                        if isRebuilding {
                            HStack {
                                ProgressView()
                                Text(String(localized: "details.rebuilding"))
                            }
                            .frame(maxWidth: .infinity)
                        } else {
                            Label(
                                session.status == .ready
                                    ? String(localized: "details.save-rebuild")
                                    : String(localized: "details.save-edits"),
                                systemImage: "checkmark.circle"
                            )
                            .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isRebuilding)

                    if session.isResumable {
                        Button {
                            saveEdits()
                            viewModel.resume(session)
                            dismiss()
                        } label: {
                            Label(String(localized: "details.continue"), systemImage: "camera.fill")
                        }

                        if session.hasRecordedContent {
                            Button {
                                saveEdits()
                                viewModel.finalize(session)
                            } label: {
                                Label(String(localized: "details.finish"), systemImage: "checkmark.circle.fill")
                            }
                            .tint(.red)
                        }
                    } else if session.status == .ready {
                        Button {
                            let updated = viewModel.sessions.first(where: { $0.id == session.id }) ?? session
                            viewModel.share(updated)
                        } label: {
                            Label(String(localized: "details.share"), systemImage: "square.and.arrow.up")
                        }
                        .disabled(isRebuilding)
                    }

                    Button(role: .destructive) {
                        deleteConfirmation = true
                    } label: {
                        Label(String(localized: "details.delete"), systemImage: "trash")
                    }
                    .disabled(isRebuilding)
                }
            }
            .navigationTitle(String(localized: "details.title-screen"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done")) {
                        if !isRebuilding {
                            saveEdits()
                            dismiss()
                        }
                    }
                    .disabled(isRebuilding)
                }
            }
            .onAppear {
                reloadPlayer()
            }
            .onChange(of: viewModel.rebuildingSessionID) { oldValue, newValue in
                if oldValue == session.id, newValue == nil {
                    reloadPlayer()
                }
            }
            .onDisappear {
                player?.pause()
            }
            .confirmationDialog(
                String(localized: "delete.confirm.title"),
                isPresented: $deleteConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "details.delete"), role: .destructive) {
                    viewModel.delete(session)
                    dismiss()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                Text(String(localized: "delete.confirm.message"))
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(isRebuilding)
    }

    private func saveEdits() {
        viewModel.saveDetailsAndOverlay(
            sessionID: session.id,
            title: title,
            caption: caption,
            timestampOverlay: timestampOverlay
        )
    }

    private func reloadPlayer() {
        player?.pause()
        let current = viewModel.sessions.first(where: { $0.id == session.id }) ?? session
        if let url = viewModel.previewURL(for: current) {
            player = AVPlayer(url: url)
        }
    }
}

private extension View {
    func badgeStyle() -> some View {
        self
            .font(.caption2.bold())
            .labelStyle(.titleAndIcon)
            .padding(.horizontal, 7)
            .padding(.vertical, 4)
            .background(.thinMaterial, in: Capsule())
    }
}
