import SwiftUI

/// “Bildir” (proposed, App Review 1.2; in the invite sheet's language): a
/// full-height C18 sheet — the sheet bar (“Vazgeç” · “Bildir”), one line
/// on where the report goes, the reasons as a C13 card on `bg` with the
/// chosen one checked (`ph:check` `accent300`), an optional note, “Bu
/// kişiyi de engelle” (C14, on by default; not shown for someone blocked
/// already), the privacy line (no message content is ever included) and,
/// pinned under the scrolling form, “E-postayla gönder” (C1, until a reason
/// is chosen at 45 %).
///
/// When nothing on this phone can write an e-mail the content cross-fades
/// to the support address with “Kopyala” (and, if the block followed,
/// “… engellendi.”) and “Tamam”.
struct ReportSheet: View {
    @Bindable var model: ReportModel
    @Environment(\.locale) private var locale
    @Environment(\.u2SafeArea) private var safeArea
    @FocusState private var isNoteFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            SheetBar(
                title: Text("report.title", table: .report),
                cancelTitle: model.phase == .address ? Text("common.close") : Text("common.cancel"),
                onCancel: model.close
            )
            .accessibilityIdentifier("report.bar")
            ZStack(alignment: .top) {
                if model.phase == .address {
                    address
                        .transition(.u2Fade)
                } else {
                    form
                        .transition(.u2Fade)
                }
            }
            .u2Animation(U2Motion.state, value: model.phase == .address)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("report.sheet")
        // Pulled down: the host hears it like “Vazgeç”.
        .onDisappear(perform: model.sheetDidDisappear)
    }

    // MARK: Form

    /// The form scrolls; “E-postayla gönder” stays pinned to the bottom, in
    /// view at any text size and above the keyboard while the note is typed.
    private var form: some View {
        GeometryReader { proxy in
            // The reader avoids the keyboard and reports it as its bottom
            // inset (U2Screen ignores the container insets): 40 at rest,
            // 12 above the keyboard, continuously with its motion.
            let keyboard = proxy.safeAreaInsets.bottom
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("report.intro \(model.personName)", table: .report)
                        .u2Font(.callout)
                        .foregroundStyle(Color.u2.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, 8)
                    reasons
                        .padding(.top, 20)
                    noteSection
                        .padding(.top, 22)
                    if model.offersBlock {
                        GroupedCard(fill: Color.u2.bg) {
                            GroupedToggleRow(
                                tile: IconTile(content: .glyph(.prohibit), tone: .danger),
                                title: Text("report.block", table: .report),
                                subtitle: Text("report.block.subtitle", table: .report),
                                isOn: $model.alsoBlock
                            )
                            .accessibilityIdentifier("report.alsoBlock")
                        }
                        .padding(.top, 22)
                    }
                    privacyNote
                        .padding(EdgeInsets(top: 16, leading: 8, bottom: 0, trailing: 8))
                }
                .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                sendBar(bottomPadding: max(12, safeArea.bottom(40) - keyboard))
            }
        }
        .disabled(model.phase == .sending)
    }

    /// After a failure the line that says so, then “E-postayla gönder”
    /// (pad 12 16, on the sheet's `surface`; the form fades out above it).
    private func sendBar(bottomPadding: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.failed {
                Text("report.failed", table: .report)
                    .u2Font(.caption)
                    .foregroundStyle(Color.u2.danger)
                    .padding(EdgeInsets(top: 0, leading: 8, bottom: 10, trailing: 8))
                    .transition(.u2Fade)
                    .accessibilityIdentifier("report.failed")
            }
            Button {
                isNoteFocused = false
                Task { await model.send(locale: locale) }
            } label: {
                U2ButtonLabel(
                    Text("report.send", table: .report), glyph: .paperPlaneTilt, isWorking: model.phase == .sending)
            }
            .buttonStyle(.u2Primary)
            .disabled(!model.canSend)
            .accessibilityHint(model.reason == nil ? Text("report.send.hint", table: .report) : Text(verbatim: ""))
            .accessibilityIdentifier("report.send")
        }
        .padding(EdgeInsets(top: 12, leading: 16, bottom: bottomPadding, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.u2.surface)
        .background(alignment: .top) {
            LinearGradient(colors: [Color.u2.surface.opacity(0), Color.u2.surface], startPoint: .top, endPoint: .bottom)
                .frame(height: 16)
                .offset(y: -16)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    /// The five reasons (C13 on `bg`), the chosen one checked.
    private var reasons: some View {
        VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("report.reason.label", table: .report))
            GroupedCard(fill: Color.u2.bg) {
                ForEach(Array(ReportReason.allCases.enumerated()), id: \.element) { index, reason in
                    if index > 0 { GroupedSeparator(inset: 14) }
                    let isSelected = model.reason == reason
                    Button {
                        guard !isSelected else { return }
                        U2Haptics.selection()
                        model.reason = reason
                    } label: {
                        GroupedRow(tile: nil, title: StringTable.report.text(reason.titleKey, locale: locale)) {
                            PhosphorIcon(.check, size: 18)
                                .foregroundStyle(Color.u2.accent300)
                                .opacity(isSelected ? 1 : 0)
                                .scaleEffect(isSelected ? 1 : 0.8)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(GroupedRowButtonStyle())
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                    .accessibilityIdentifier("report.reason.\(reason.rawValue)")
                }
            }
            .u2Animation(U2Motion.micro, value: model.reason)
        }
    }

    /// “Not · isteğe bağlı”: a short text field on `bg` (at most 500 characters).
    private var noteSection: some View {
        let shape = RoundedRectangle(cornerRadius: U2Radius.field, style: .continuous)
        return VStack(alignment: .leading, spacing: U2Spacing.sectionLabelGap) {
            SectionLabel(Text("report.note.label", table: .report))
            TextEditor(text: $model.note)
                .focused($isNoteFocused)
                .u2Font(.bubble, matchLineBox: false)
                .foregroundStyle(Color.u2.text)
                .tint(Color.u2.accent)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 96, maxHeight: 160)
                .padding(EdgeInsets(top: 6, leading: 9, bottom: 6, trailing: 9))
                .background(alignment: .topLeading) {
                    if model.note.isEmpty {
                        Text("report.note.placeholder", table: .report)
                            .u2Font(.bubble, matchLineBox: false)
                            .foregroundStyle(Color.u2.textPlaceholder)
                            .padding(EdgeInsets(top: 14, leading: 14, bottom: 0, trailing: 14))
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
                .background(shape.fill(Color.u2.bg))
                .overlay(shape.strokeBorder(Color.u2.accent, lineWidth: 1.5).opacity(isNoteFocused ? 1 : 0))
                .u2Animation(U2Motion.state, value: isNoteFocused)
                .accessibilityLabel(Text("a11y.report.note", table: .report))
                .accessibilityIdentifier("report.note")
        }
    }

    /// As the invite's: glyph 18 + `callout`.
    private var privacyNote: some View {
        HStack(alignment: .top, spacing: 12) {
            PhosphorIcon(.shieldCheck, size: 18)
                .foregroundStyle(Color.u2.accent300)
                .padding(.top, 1)
            Text("report.privacy", table: .report)
                .u2Font(.callout)
                .foregroundStyle(Color.u2.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("report.privacy")
    }

    // MARK: No e-mail on this phone

    /// The support address to write to (C13 row on `bg`, Mono, “Kopyala”),
    /// whether the block followed, and “Tamam”.
    private var address: some View {
        ScrollView {
            VStack(spacing: 0) {
                VStack(spacing: 14) {
                    IconTile(content: .glyph(.paperPlaneTilt), size: 52, cornerRadius: 16, glyphSize: 24)
                    VStack(spacing: 6) {
                        Text("report.address.title", table: .report)
                            .u2Font(.rowTitleEmphasis)
                            .foregroundStyle(Color.u2.text)
                            .accessibilityAddTraits(.isHeader)
                        Text("report.address.message", table: .report)
                            .u2Font(.callout)
                            .foregroundStyle(Color.u2.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .multilineTextAlignment(.center)
                }
                .padding(.horizontal, 16)
                .padding(.top, 28)
                GroupedCard(fill: Color.u2.bg) {
                    HStack(spacing: 12) {
                        Text(verbatim: ReportMail.address)
                            .u2Font(.monoUsername)
                            .foregroundStyle(Color.u2.text)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("report.address")
                        Button {
                            model.copyAddress(locale: locale)
                        } label: {
                            HStack(spacing: 6) {
                                PhosphorIcon(model.copiedAddress ? .check : .copy, size: 18)
                                if model.copiedAddress {
                                    Text("report.address.copied", table: .report)
                                } else {
                                    Text("report.address.copy", table: .report)
                                }
                            }
                            .contentTransition(.opacity)
                        }
                        .buttonStyle(U2TextButtonStyle(tone: .accent, height: 44, fillsWidth: false))
                        .accessibilityIdentifier("report.copy")
                    }
                    .padding(.leading, 16)
                    .padding(.trailing, 12)
                    .frame(minHeight: 56)
                }
                .padding(.top, 24)
                if model.didBlock {
                    HStack(spacing: 8) {
                        PhosphorIcon(.prohibit, size: 16)
                            .foregroundStyle(Color.u2.danger)
                        Text("report.blocked \(model.personName)", table: .report)
                            .u2Font(.caption)
                            .foregroundStyle(Color.u2.textSecondary)
                    }
                    .padding(.top, 14)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("report.blocked")
                }
                Button(action: model.close) {
                    U2ButtonLabel(Text("report.done", table: .report))
                }
                .buttonStyle(.u2Primary)
                .accessibilityIdentifier("report.done")
                .padding(.top, 28)
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 0, trailing: 16))
            .u2Bottom(40)
        }
        .scrollIndicators(.hidden)
        .scrollBounceBehavior(.basedOnSize)
        .accessibilityIdentifier("report.fallback")
    }
}

#Preview("Bildir") {
    let model = ReportModel(
        subject: ReportSubject(username: "kuzey_dev", isUsernameVerified: true), personName: "kuzey_dev",
        offersBlock: true
    ) {}
    model.reason = .spam
    return ReportSheet(model: model)
        .padding(.top, 40)
        .background(Color.u2.surface)
        .environment(\.locale, Locale(identifier: "tr"))
}
