import SwiftUI

/// 2.3 · Geri dönüş yok — the irreversibility, said plainly; Face ID offered;
/// “Kurulumu tamamla” only after the acknowledgement is ticked.
struct NoWayBackStepView: View {
    let model: NoWayBackStepModel
    let step: (step: Int, count: Int)
    let onBack: () -> Void

    @Environment(\.locale) private var locale

    var body: some View {
        U2Screen {
            OnboardingFitOrScroll {
                VStack(spacing: 0) {
                    SetupHeader(step: step.step, stepCount: step.count, onBack: onBack)
                        .disabled(model.isWorking)
                    VStack(alignment: .leading, spacing: U2Spacing.titleToBodyGap) {
                        SetupTitle(
                            StringTable.onboarding.resource("onboarding.noWayBack.title"), style: .displayTitleS)
                        LocalizedText("onboarding.noWayBack.body", table: .onboarding)
                            .u2Font(.body)
                            .foregroundStyle(Color.u2.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 18)
                    .padding(.horizontal, U2Spacing.screenGutterL)

                    factsCard
                        .padding(.top, 20)
                        .padding(.horizontal, U2Spacing.screenGutterS)

                    if model.biometry != .none {
                        biometryCard
                            .padding(.top, 12)
                            .padding(.horizontal, U2Spacing.screenGutterS)
                    }

                    Spacer(minLength: 0)

                    if model.failed {
                        LocalizedText("onboarding.noWayBack.error", table: .onboarding)
                            .u2Font(.calloutL)
                            .foregroundStyle(Color.u2.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, U2Spacing.screenGutterL)
                            .padding(.bottom, 14)
                            .transition(.u2Rise)
                    }

                    Toggle(isOn: Bindable(model).acknowledged) {
                        LocalizedText("onboarding.noWayBack.acknowledge", table: .onboarding)
                            .u2Font(.calloutL)
                            .foregroundStyle(Color.u2.text)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .toggleStyle(.u2Checkbox)
                    .disabled(model.isWorking)
                    .accessibilityIdentifier("onboarding.acknowledge")
                    .padding(.horizontal, U2Spacing.screenGutterL)
                    .padding(.bottom, 18)

                    Button(action: model.submit) {
                        HStack(spacing: 10) {
                            if model.isWorking {
                                SpinningGlyph(size: 20)
                                    .transition(.u2Fade)
                            }
                            LocalizedText("onboarding.noWayBack.finish", table: .onboarding)
                        }
                        .u2Animation(U2Motion.state, value: model.isWorking)
                    }
                    .buttonStyle(.u2Primary)
                    .disabled(!model.acknowledged)
                    .allowsHitTesting(!model.isWorking)
                    .accessibilityIdentifier("onboarding.finish")
                    .padding(.horizontal, U2Spacing.screenGutter)
                    .u2Bottom(U2Spacing.ctaBottom)
                }
                .u2Animation(U2Motion.state, value: model.failed)
            }
        }
    }

    /// Three facts on `surface` r16; rows 52 / 52 / 62 high, pad 0 16, gap
    /// 14, 21 pt icons; *solid* 1 pt `divider` separators inset 51.
    private var factsCard: some View {
        VStack(spacing: 0) {
            factRow(glyph: .prohibit, tint: Color.u2.danger, title: "onboarding.noWayBack.fact.noReset")
            factSeparator
            factRow(glyph: .cloudSlash, tint: Color.u2.textSecondary, title: "onboarding.noWayBack.fact.neverSent")
            factSeparator
            factRow(
                glyph: .fileLock, tint: Color.u2.accent300, title: "onboarding.noWayBack.fact.backup",
                subtitle: "onboarding.noWayBack.fact.backup.path", minHeight: 62)
        }
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.u2.surface))
    }

    private var factSeparator: some View {
        Rectangle()
            .fill(Color.u2.divider)
            .frame(height: 1)
            .padding(.leading, 51)
            .accessibilityHidden(true)
    }

    private func factRow(
        glyph: PhosphorGlyph, tint: Color, title: String.LocalizationValue,
        subtitle: String.LocalizationValue? = nil, minHeight: CGFloat = 52
    ) -> some View {
        HStack(alignment: .center, spacing: 14) {
            PhosphorIcon(glyph, size: 21)
                .foregroundStyle(tint)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                StringTable.onboarding.text(title, locale: locale)
                    .u2Font(.rowTitleS)
                    .foregroundStyle(Color.u2.text)
                if let subtitle {
                    StringTable.onboarding.text(subtitle, locale: locale)
                        .u2Font(.caption)
                        .foregroundStyle(Color.u2.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .frame(minHeight: minHeight)
        .accessibilityElement(children: .combine)
    }

    /// Face ID card: the design's Face ID glyph (22, stroke 1.7, `accent300`),
    /// “Face ID ile aç” + “Tanımazsa kod istenir”, C14 switch (ON by default).
    private var biometryCard: some View {
        Toggle(isOn: Bindable(model).faceIDEnabled) {
            HStack(alignment: .center, spacing: 14) {
                Group {
                    if model.biometry == .touchID {
                        PhosphorIcon(.lockSimple, size: 22)
                    } else {
                        FaceIDGlyph(size: 22, lineWidth: 1.7)
                    }
                }
                .foregroundStyle(Color.u2.accent300)
                .frame(width: 22, height: 22)
                VStack(alignment: .leading, spacing: 2) {
                    LocalizedText(
                        model.biometry == .touchID ? "onboarding.noWayBack.touchID" : "onboarding.noWayBack.faceID"
                    , table: .onboarding)
                    .u2Font(.rowTitleS)
                    .foregroundStyle(Color.u2.text)
                    LocalizedText("onboarding.noWayBack.biometry.fallback", table: .onboarding)
                        .u2Font(.caption)
                        .foregroundStyle(Color.u2.textSecondary)
                }
            }
        }
        .toggleStyle(.u2)
        .disabled(model.isWorking)
        .padding(.horizontal, 16)
        .frame(minHeight: 64)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.u2.surface))
        .accessibilityIdentifier("onboarding.faceID")
    }
}
