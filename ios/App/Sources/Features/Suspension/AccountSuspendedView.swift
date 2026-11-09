import SwiftUI
import UIKit

/// “Hesabın askıya alındı” (proposed; App Review 1.2): the relay answers
/// every request of this account `403 account_suspended` and closed its
/// connection with `4403` (PROTOCOL §4.3, §4.7). It covers the unlocked app
/// in LockWipedScreen's language — the 52 pt badge in the danger tint, the
/// Doto title, the sentence in `calloutL` — with a note that nothing on the
/// phone was deleted, and 3.3's option cards on a `surface` panel: write to
/// support (Mail, another mail app, or the address to copy), and the Terms
/// of Use on us2us.app. Under them “Her şeyi sil” opens 7.1's own erase
/// sheet (the relay refuses the deletion, so it offers this phone only).
/// Nothing is retried until the next unlock; a lifted suspension simply
/// lets the line open again then.
struct AccountSuspendedView: View {
    let messenger: any MessengerService
    let onErase: @MainActor () async -> Void
    @State private var model: AccountSuspendedModel
    @State private var erasure: EraseAccountModel
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    init(
        messenger: any MessengerService, mailer: any ReportMailing = SystemReportMailer(),
        onErase: @escaping @MainActor () async -> Void
    ) {
        self.messenger = messenger
        self.onErase = onErase
        _model = State(initialValue: AccountSuspendedModel(mailer: mailer))
        _erasure = State(initialValue: EraseAccountModel(messenger: messenger))
    }

    var body: some View {
        U2Screen(background: Color.u2.bg) {
            OnboardingFitOrScroll {
                VStack(spacing: 0) {
                    notice
                        .padding(.horizontal, U2Spacing.screenGutterL)
                        .u2Top(106)

                    Spacer(minLength: 28)

                    VStack(spacing: 14) {
                        if model.phase == .address { address.transition(.u2Fade) }
                        options
                        Button { erasure.present() } label: {
                            Text("settings.erase", table: .settings)
                        }
                        .buttonStyle(U2TextButtonStyle(tone: .danger, height: U2Size.textButtonS))
                        .accessibilityIdentifier("suspended.erase")
                    }
                    .padding(.horizontal, U2Spacing.screenGutterS)
                    .u2Bottom(U2Spacing.ctaBottomOnboarding)
                }
            }
        }
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("suspended.screen")
        .onAppear(perform: announce)
        .u2Sheet(
            isPresented: Bindable(erasure).isPresented, style: .floating, isDismissible: !erasure.isWorking,
            onDismiss: eraseAfterSheet
        ) {
            EraseAccountSheet(model: erasure)
        }
    }

    private var notice: some View {
        VStack(spacing: 16) {
            PhosphorIcon(.prohibit, size: 24)
                .foregroundStyle(Color.u2.danger)
                .frame(width: U2Size.lockIcon, height: U2Size.lockIcon)
                .background(Circle().fill(Color.u2.dangerTint))
                .accessibilityHidden(true)
            U2DisplayText(
                verbatim: StringTable.suspension.string("suspended.title", locale: locale),
                style: .displayTitleXS.balanced, alignment: .center, isHeader: true)
            StringTable.suspension.text("suspended.message", locale: locale)
                .u2Font(.calloutL)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 340)
                .accessibilityIdentifier("suspended.message")
            StringTable.suspension.text("suspended.note", locale: locale)
                .u2Font(.caption)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: 320)
                .accessibilityIdentifier("suspended.note")
        }
    }

    private var options: some View {
        VStack(spacing: 10) {
            OptionCard(
                glyph: .paperPlaneTilt, title: StringTable.suspension.text("suspended.write", locale: locale),
                description: Text(verbatim: ReportMail.address)
            ) {
                Task { await model.writeToSupport(username: messenger.account?.username, locale: locale) }
            }
            .accessibilityIdentifier("suspended.mail")
            OptionCard(
                glyph: .fileLock, title: StringTable.suspension.text("suspended.terms", locale: locale),
                description: Text(verbatim: WebsitePage.host)
            ) {
                guard let url = WebsitePage.terms.url(for: locale) else { return }
                openURL(url)
            }
            .accessibilityHint(StringTable.suspension.text("suspended.terms.hint", locale: locale))
            .accessibilityIdentifier("suspended.terms")
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: U2Radius.cardL, style: .continuous).fill(Color.u2.surface))
    }

    /// No mail app on this phone: the address to copy (the report sheet's row).
    private var address: some View {
        VStack(alignment: .leading, spacing: 8) {
            StringTable.suspension.text("suspended.address", locale: locale)
                .u2Font(.caption)
                .foregroundStyle(Color.u2.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
            GroupedCard(fill: Color.u2.surface) {
                HStack(spacing: 12) {
                    Text(verbatim: ReportMail.address)
                        .u2Font(.monoUsername)
                        .foregroundStyle(Color.u2.text)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("suspended.address")
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
                    .accessibilityIdentifier("suspended.copy")
                }
                .padding(.leading, 16)
                .padding(.trailing, 12)
                .frame(minHeight: 56)
            }
        }
    }

    /// The erase sheet is completely gone: wipe now if that was decided.
    private func eraseAfterSheet() {
        let erasure = erasure
        Task { await erasure.sheetDidDismiss { await onErase() } }
    }

    /// VoiceOver hears what happened as soon as the screen is there.
    private func announce() {
        guard UIAccessibility.isVoiceOverRunning else { return }
        let text = StringTable.suspension.string("suspended.title", locale: locale) + " "
            + StringTable.suspension.string("suspended.message", locale: locale)
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}

#Preview("Hesabın askıya alındı") {
    AccountSuspendedView(messenger: AppModel.preview(start: .chats).messenger, onErase: {})
        .environment(\.locale, Locale(identifier: "tr"))
}
