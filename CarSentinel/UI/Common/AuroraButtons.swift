import SwiftUI

// === Aurora UI 按钮（iOS / SwiftUI）===
// 硬规则：
//   1. 按钮文字必须居中 —— 统一用 Button + 居中的 label，不要自己拼 HStack 挤文案。
//   2. 底一律是**透明玻璃**（见 AuroraGlass.swift），不再用实心色块。
//      品牌色只作为「染色 + 文字色」出现，这样底下的极光背景能透上来。

/// 按钮文字：图标 + 文案，整体水平垂直居中。
///
/// - `expands`: 是否撑满父容器宽度。
///   主按钮（底部操作条里成对出现的那种）用 `true`；
///   列表行里的小按钮用 `false`，否则会抢掉整行的空间。
struct AuroraButtonLabel: View {

    let text: String
    var systemIcon: String? = nil
    var height: CGFloat = AuroraMetrics.primaryButtonHeight
    var font: Font = AuroraFont.labelLarge
    var expands: Bool = true

    var body: some View {
        content
            .frame(maxWidth: expands ? .infinity : nil)
            .frame(height: height)
    }

    private var content: some View {
        HStack(spacing: 6) {
            if let systemIcon {
                Image(systemName: systemIcon)
                    .font(.system(size: 15, weight: .semibold))
            }
            Text(text)
                .font(font)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .fixedSize(horizontal: !expands, vertical: false)
    }
}

/// 主按钮：品牌色玻璃 + 白字。
///
/// 「染色而不是实心」是这次改版的关键 —— 实心色块和玻璃底栏放在一起会显得闷。
struct PrimaryButton: View {

    let text: String
    var systemIcon: String? = nil
    var enabled: Bool = true
    var expands: Bool = true
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            AuroraButtonLabel(
                text: text,
                systemIcon: systemIcon,
                expands: expands
            )
            .foregroundStyle(Color.white)
            .background {
                ZStack {
                    AuroraShape.button.fill(.ultraThinMaterial)
                    AuroraShape.button
                        .fill(Aurora.brand)
                        .opacity(scheme == .dark ? 0.74 : 0.86)
                    AuroraShape.button.fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.24), Color.white.opacity(0.02)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }
            }
            .clipShape(AuroraShape.button)
            .overlay {
                AuroraShape.button.strokeBorder(Color.white.opacity(0.34), lineWidth: 1)
            }
            .shadow(color: Aurora.brand.opacity(0.28), radius: 12, x: 0, y: 6)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// 次按钮：中性玻璃 + 品牌色文字。
struct TonalButton: View {

    let text: String
    var systemIcon: String? = nil
    var enabled: Bool = true
    var expands: Bool = true
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            AuroraButtonLabel(
                text: text,
                systemIcon: systemIcon,
                expands: expands
            )
            .foregroundStyle(Aurora.readableBrand(scheme))
            .auroraGlass(AuroraShape.button, tint: Aurora.brand, tintOpacity: 0.10)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// 主 CTA：品牌渐变玻璃 + 白字。一个页面最多一个。
struct GradientButton: View {

    let text: String
    var systemIcon: String? = nil
    var enabled: Bool = true
    var expands: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            AuroraButtonLabel(
                text: text,
                systemIcon: systemIcon,
                expands: expands
            )
            .foregroundStyle(Color.white)
            .auroraGlassGradient(cornerRadius: 16, enabled: enabled)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.6)
    }
}

/// 小按钮（38pt 高，13pt 圆角）。
struct SmallTonalButton: View {

    let text: String
    var systemIcon: String? = nil
    var enabled: Bool = true
    var expands: Bool = false
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            AuroraButtonLabel(
                text: text,
                systemIcon: systemIcon,
                height: AuroraMetrics.smallButtonHeight,
                font: AuroraFont.labelMedium,
                expands: expands
            )
            .padding(.horizontal, expands ? 0 : 12)
            .foregroundStyle(Aurora.readableBrand(scheme))
            .auroraGlass(cornerRadius: 13, tint: Aurora.brand, tintOpacity: 0.10, shadowRadius: 6)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// 危险操作按钮：玻璃 + 玫瑰色染色与文字。
struct DangerTonalButton: View {

    let text: String
    var systemIcon: String? = nil
    var enabled: Bool = true
    var expands: Bool = true
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            AuroraButtonLabel(
                text: text,
                systemIcon: systemIcon,
                expands: expands
            )
            .foregroundStyle(Aurora.readableRose(scheme))
            .auroraGlass(AuroraShape.button, tint: Aurora.rose, tintOpacity: 0.16)
        }
        .buttonStyle(PressScaleStyle())
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.45)
    }
}

/// 文字按钮（对应 Material 的 TextButton）。
/// 也走玻璃胶囊 —— 否则在一堆玻璃按钮里会显得突兀。
struct TextLinkButton: View {

    let text: String
    var systemIcon: String? = nil
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemIcon {
                    Image(systemName: systemIcon)
                        .font(.system(size: 13, weight: .semibold))
                }
                Text(text)
                    .font(AuroraFont.labelMedium)
                    .lineLimit(1)
            }
            .foregroundStyle(Aurora.readableBrand(scheme))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .auroraGlass(cornerRadius: 10, tint: Aurora.brand, tintOpacity: 0.06, shadowRadius: 0)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
