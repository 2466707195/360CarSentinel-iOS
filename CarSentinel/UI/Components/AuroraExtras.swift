import SwiftUI

// =============================================================================
// Aurora 小零件
//
// 只做本 App 需要、但技能资产里没覆盖的几件：小标题、进度条、开关、筛选胶囊、
// 信息行、指标块、顶部提示条。
// 全部遵守：实白底 / 不透明 / 无半透明叠色 / 文字居中。
// =============================================================================

// MARK: - 连接状态配色

extension LinkState {
    var color: Color {
        switch self {
        case .mock: return Aurora.warning
        case .connecting: return Aurora.info
        case .online: return Aurora.success
        case .failed: return Aurora.rose
        }
    }

    var icon: String {
        switch self {
        case .mock: return "sparkles"
        case .connecting: return "arrow.triangle.2.circlepath"
        case .online: return "checkmark.circle"
        case .failed: return "exclamationmark.triangle"
        }
    }
}

// MARK: - 区块小标题

struct SectionHeader: View {

    let title: String
    var detail: String? = nil
    var actionTitle: String? = nil
    var onAction: (() -> Void)? = nil

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Text(title)
                .font(AuroraFont.titleMedium)
                .foregroundStyle(Aurora.ink)
            if let detail {
                Text(detail)
                    .font(AuroraFont.labelSmall)
                    .foregroundStyle(Aurora.muted)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            if let actionTitle, let onAction {
                TextLinkButton(text: actionTitle, action: onAction)
            }
        }
        .padding(.horizontal, 4)
        .padding(.top, 8)
        .padding(.bottom, 2)
    }
}

// MARK: - 进度条

struct MeterBar: View {

    var progress: Double
    var tint: Color = Aurora.brand
    var height: CGFloat = 8

    private var clamped: Double {
        min(1, max(0, progress))
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(tint.tint(0.13))
                Capsule()
                    .fill(tint)
                    .frame(width: max(height, geo.size.width * clamped))
            }
        }
        .frame(height: height)
    }
}

// MARK: - 自绘开关

struct AuroraSwitch: View {

    @Binding var isOn: Bool
    var onColor: Color = Aurora.brand

    var body: some View {
        Button {
            withAnimation(.easeOut(duration: 0.18)) { isOn.toggle() }
        } label: {
            Capsule()
                .fill(isOn ? onColor : Aurora.surfaceVariant)
                .frame(width: 46, height: 28)
                .overlay(
                    Circle()
                        .fill(Color.white)
                        .frame(width: 22, height: 22)
                        .shadow(color: Color.black.opacity(0.14), radius: 2, x: 0, y: 1)
                        .offset(x: isOn ? 9 : -9)
                )
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 筛选胶囊

struct ChipButton: View {

    let title: String
    var systemIcon: String? = nil
    var selected: Bool = false
    var tint: Color = Aurora.brand
    var count: Int? = nil
    let action: () -> Void

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemIcon {
                    Image(systemName: systemIcon)
                        .font(.system(size: 11, weight: .semibold))
                }
                Text(title)
                    .font(AuroraFont.labelMedium)
                    .lineLimit(1)
                if let count, count > 0 {
                    Text("\(count)")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(selected ? Color.white : tint)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 1)
                        .background(
                            Capsule().fill(selected ? Color.white.opacity(0.26) : tint.opacity(0.14))
                        )
                }
            }
            .foregroundStyle(selected ? Color.white : Aurora.readableSoft(scheme))
            .padding(.horizontal, 11)
            .padding(.vertical, 7)
            // 选中 = 品牌色玻璃，未选中 = 中性玻璃。两者都是半透明，只是染色不同
            .auroraGlassCapsule(
                tint: selected ? tint : Aurora.brand,
                tintOpacity: selected ? (scheme == .dark ? 0.80 : 0.88) : 0.05
            )
        }
        .buttonStyle(PressScaleStyle(scale: 0.96))
    }
}

// MARK: - 信息行

struct InfoRow: View {

    let systemIcon: String
    let title: String
    var value: String = ""
    var valueTint: Color = Aurora.ink
    var chevron: Bool = false

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemIcon)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Aurora.brand)
                .frame(width: 26, height: 26)
                .background(
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(Aurora.brand.tint(0.11))
                )

            Text(title)
                .font(AuroraFont.bodyMedium)
                .foregroundStyle(Aurora.ink)

            Spacer(minLength: 8)

            if !value.isEmpty {
                Text(value)
                    .font(AuroraFont.bodySmall)
                    .foregroundStyle(valueTint)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            if chevron {
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Aurora.muted)
            }
        }
        .padding(.vertical, 9)
        .contentShape(Rectangle())
    }
}

// MARK: - 指标块

struct MetricTile: View {

    let label: String
    let value: String
    var unit: String? = nil
    var tint: Color = Aurora.brand
    var systemIcon: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 3) {
                if let systemIcon {
                    Image(systemName: systemIcon)
                        .font(.system(size: 9.5, weight: .semibold))
                }
                Text(label)
                    .font(AuroraFont.labelSmall)
            }
            .foregroundStyle(Aurora.muted)
            .lineLimit(1)

            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(tint)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if let unit {
                    Text(unit)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(tint.opacity(0.8))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - 细分隔线

struct HairLine: View {
    var body: some View {
        Rectangle()
            .fill(Aurora.hairline)
            .frame(height: 1)
    }
}

// MARK: - 顶栏图标按钮

/// 44pt 命中区的标准图标按钮。等价于技能资产 `AuroraToolbar.swift` 里的同名组件，
/// 但去掉了 iOS 26 的 `ToolbarSpacer` 依赖，保证低 SDK 也编得过。
struct AuroraToolbarIconButton: View {

    private let systemImage: String
    private let accessibilityLabel: String
    private let tint: Color
    private let action: () -> Void

    init(
        _ systemImage: String,
        accessibilityLabel: String,
        tint: Color = Aurora.ink,
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.accessibilityLabel = accessibilityLabel
        self.tint = tint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: AuroraMetrics.iconButtonSize, height: AuroraMetrics.iconButtonSize)
                .auroraGlassCircle(
                    diameter: AuroraMetrics.iconButtonSize,
                    tint: Aurora.brand,
                    tintOpacity: 0.05
                )
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 顶部提示条

struct BannerOverlay: ViewModifier {

    @Binding var message: String?

    func body(content: Content) -> some View {
        ZStack(alignment: .top) {
            content

            if let message {
                HStack(spacing: 7) {
                    Image(systemName: "info.circle")
                        .font(.system(size: 12, weight: .semibold))
                    Text(message)
                        .font(AuroraFont.labelMedium)
                        .lineLimit(2)
                }
                .foregroundStyle(Aurora.onBrandContainer)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(
                    Capsule().fill(Aurora.brandContainer)
                )
                .overlay(
                    Capsule().stroke(Aurora.brand.tint(0.35), lineWidth: 1)
                )
                .padding(.horizontal, AuroraMetrics.pagePadding)
                .padding(.top, 6)
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: message) {
                    try? await Task<Never, Never>.sleep(nanoseconds: 2_600_000_000)
                    withAnimation(.easeOut(duration: 0.2)) {
                        self.message = nil
                    }
                }
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.85), value: message)
    }
}

extension View {
    func banner(_ message: Binding<String?>) -> some View {
        modifier(BannerOverlay(message: message))
    }
}
