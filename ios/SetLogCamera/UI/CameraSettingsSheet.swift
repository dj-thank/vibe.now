import AVKit
import SwiftUI
import UIKit

private let sharedThumbnailGenerator = VideoThumbnailGenerator()

struct GalleryScreen: View {
    @ObservedObject var viewModel: CameraViewModel

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
                    ContentUnavailableView(
                        String(localized: "gallery.empty.title"),
                        systemImage: "video.slash",
                        description: Text(String(localized: "gallery.empty.message"))
                    )
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
                ToolbarItem(placement: .topBarTrailing) {
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
    @State private var player: AVPlayer?
    @State private var deleteConfirmation = false

    init(viewModel: CameraViewModel, session: SetLogSession) {
        self.viewModel = viewModel
        self.session = session
        _title = State(initialValue: session.title)
        _caption = State(initialValue: session.caption)
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
                    Button(String(localized: "details.save-edits")) {
                        saveEdits()
                    }
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
                            saveEdits()
                            let updated = viewModel.sessions.first(where: { $0.id == session.id }) ?? session
                            viewModel.share(updated)
                        } label: {
                            Label(String(localized: "details.share"), systemImage: "square.and.arrow.up")
                        }
                    }

                    Button(role: .destructive) {
                        deleteConfirmation = true
                    } label: {
                        Label(String(localized: "details.delete"), systemImage: "trash")
                    }
                }
            }
            .navigationTitle(String(localized: "details.title-screen"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(String(localized: "common.done")) {
                        saveEdits()
                        dismiss()
                    }
                }
            }
            .onAppear {
                if let url = viewModel.previewURL(for: session) {
                    player = AVPlayer(url: url)
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
    }

    private func saveEdits() {
        viewModel.saveDetails(sessionID: session.id, title: title, caption: caption)
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
