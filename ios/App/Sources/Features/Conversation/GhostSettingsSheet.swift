import SwiftUI

/// 5.5 · Ghost ve süreli mesaj — opened from the header counter (or the
/// composer's timer key). Per chat, for both sides; saving posts a system
/// line in the chat. Content-height sheet (C18), pad 8 18 40, gap 20:
/// kicker “GHOST MOD · DENİZ”, the live Doto countdown, the explanation; the
/// Ghost toggle and the silence-period chips; a dotted rule; the
/// disappearing-message chips; “Kaydet”.
struct GhostSettingsSheet: View {
    let chat: ChatSummary
    let onSave: (_ enabled: Bool, _ period: GhostPeriod, _ disappearing: DisappearingTimer) -> Void
    /// “Kaydet” with nothing changed just closes (saving would restart the timer).
    let onClose: () -> Void

    @State private var isEnabled: Bool
    @State private var period: GhostPeriod
    @State private var disappearing: DisappearingTimer

    init(chat: ChatSummary, onSave: @escaping (Bool, GhostPeriod, DisappearingTimer) -> Void, onClose: @escaping () -> Void) {
        self.chat = chat
        self.onSave = onSave
        self.onClose = onClose
        _isEnabled = State(initialValue: chat.ghost.isEnabled)
        _period = State(initialValue: chat.ghost.period)
        _disappearing = State(initialValue: chat.disappearing)
    }

    private var hasChanges: Bool {
        isEnabled != chat.ghost.isEnabled || (isEnabled && period != chat.ghost.period) || disappearing != chat.disappearing
    }

    var body: some View {
        VStack(spacing: 20) {
            hero
            ghostBlock
            DottedRule(color: Color.u2.dotRule)
            disappearingBlock
            // Always enabled, as drawn (the inventory's “only when changed” is a
            // proposal the reference does not show).
            Button {
                if hasChanges { onSave(isEnabled, period, disappearing) } else { onClose() }
            } label: {
                U2ButtonLabel("common.save")
            }
            .buttonStyle(.u2Primary)
            .accessibilityIdentifier("ghost.save")
        }
        .padding(.top, 20)
        .padding(.horizontal, 18)
        .u2Bottom(40)
    }

    // MARK: Hero

    private var hero: some View {
        VStack(spacing: 12) {
            MonoKicker(title: Text("ghost.kicker \(chat.peer.username)", table: .conversation), glyph: .ghost, glyphSize: 15)
            countdown
                .frame(minHeight: 56)
            Text("ghost.caption", table: .conversation)
                .u2Font(.sheetCaption)
                .foregroundStyle(Color.u2.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 320)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 10)
        .frame(maxWidth: .infinity)
    }

    /// Live while the saved setting is shown; the full new period once another
    /// one is picked (saving restarts the timer); dimmed while Ghost is off.
    @ViewBuilder private var countdown: some View {
        let isLive = isEnabled && chat.ghost.isEnabled && period == chat.ghost.period && chat.ghost.deadline != nil
        Group {
            if isLive, let deadline = chat.ghost.deadline {
                GhostCountdown(deadline: deadline)
            } else {
                Text(verbatim: U2Format.ghostLong(period.duration))
                    .u2Font(.displayCountdownM)
                    .foregroundStyle(Color.u2.accent200)
                    .contentTransition(.numericText())
            }
        }
        .opacity(isEnabled ? 1 : U2Opacity.disabled)
        .u2Animation(U2Motion.state, value: isEnabled)
        .u2Animation(U2Motion.state, value: period)
    }

    // MARK: Ghost

    private var ghostBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: $isEnabled) {
                Text("ghost.toggle", table: .conversation)
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(Color.u2.text)
            }
            .toggleStyle(.u2)
            .accessibilityIdentifier("ghost.toggle")
            // The reference paints the toggle row 2/3 pt and the label 1 pt
            // above their CSS boxes (its 3x layout); painted where drawn,
            // the layout itself is the design's.
            .offset(y: -2.0 / 3.0)
            SectionLabel(Text("ghost.silence", table: .conversation), horizontalPadding: 0)
                .offset(y: -1)
            SegmentedChips(options: GhostPeriod.allCases, selection: $period) { Text($0.chipKey) }
                .disabled(!isEnabled)
                .u2Animation(U2Motion.state, value: isEnabled)
        }
    }

    // MARK: Disappearing messages

    private var disappearingBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("disappearing.title", table: .conversation)
                    .u2Font(.rowTitleEmphasis)
                    .foregroundStyle(Color.u2.text)
                Text("disappearing.caption", table: .conversation)
                    .u2Font(.captionTighter)
                    .foregroundStyle(Color.u2.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    // Painted 1 pt above its box in the reference (as the label above).
                    .offset(y: -1)
            }
            SegmentedChips(options: DisappearingTimer.allCases, selection: $disappearing) { Text($0.shortKey) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
