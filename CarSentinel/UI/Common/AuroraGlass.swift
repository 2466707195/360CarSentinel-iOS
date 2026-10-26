import SwiftUI

// === Aurora 玻璃材质 ===
//
// 全 App 的按钮 / 底栏 / 操作条统一走这套「透明玻璃」：
//   · 底层 `.ultraThinMaterial` —— 真正的系统级实时模糊，深色浅色自适应
//   · 中间一层可选色染 —— 表达「主按钮 / 危险按钮」的语义
//   · 顶层一道自上而下的白色高光 —— 制造玻璃厚度，缺了这层就只是一块半透明色块
//   · 双层描边：外圈亮、内圈淡，边缘才「立」得起来
//
// 硬规则：**不要** 用 iOS 26 的 glassEffect / GlassEffectContainer。
// 那样会把最低 SDK 顶到 26，云端 CI 一旦跑旧镜像直接编不过。
// Material 是 iOS 15 就有的，效果接近，兼容性安全得多。

enum AuroraGlassTokens {

    // 高光：上亮下暗
    static let lightHighlightTop = Color.white.opacity(0.62)
    static let lightHighlightBottom = Color.white.opacity(0.05)
    static let darkHighlightTop = Color.white.opacity(0.10)
    static let darkHighlightBottom = Color.white.opacity(0.0)

    // 外圈亮边
    static let lightStrokeTop = Color.white.opacity(0.95)
    static let lightStrokeBottom = Color.white.opacity(0.35)
    static let darkStrokeTop = Color.white.opacity(0.22)
    static let darkStrokeBottom = Color.white.opacity(0.04)

    // 投影（浅色下要很轻，重了就像贴纸）
    static let lightShadow = Color(red: 0.06, green: 0.06, blue: 0.16).opacity(0.10)
    static let darkShadow = Color.black.opacity(0.42)
}

// MARK: - 玻璃背景

// 注意泛型约束是 InsettableShape 而不是 Shape：
// 内圈的 `strokeBorder` 只挂在 InsettableShape 上，写成 Shape 会直接编不过。
// 我们用到的形状（RoundedRectangle / Capsule / Circle）全都是漏空形状，约束不损失表达力。
struct AuroraGlassModifier<S: InsettableShape>: ViewModifier {

    var shape: S
    var tint: Color?
    var tintOpacity: Double
    var shadowRadius: CGFloat

    @Environment(\.colorScheme) private var scheme

    private var isDark: Bool { scheme == .dark }

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)

                    if let tint {
                        shape.fill(tint.opacity(isDark ? tintOpacity * 0.9 : tintOpacity))
                    }

                    shape.fill(
                        LinearGradient(
                            colors: isDark
                                ? [AuroraGlassTokens.darkHighlightTop, AuroraGlassTokens.darkHighlightBottom]
                                : [AuroraGlassTokens.lightHighlightTop, AuroraGlassTokens.lightHighlightBottom],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }
            }
            .clipShape(shape)
            .overlay {
                shape.strokeBorder(
                    LinearGradient(
                        colors: isDark
                            ? [AuroraGlassTokens.darkStrokeTop, AuroraGlassTokens.darkStrokeBottom]
                            : [AuroraGlassTokens.lightStrokeTop, AuroraGlassTokens.lightStrokeBottom],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
            }
            .overlay {
                // 极淡的墨色内圈：白底卡片上也能看出边界
                shape.strokeBorder(
                    Aurora.line.opacity(isDark ? 0.20 : 0.30),
                    lineWidth: 0.6
                )
            }
            .shadow(
                color: isDark ? AuroraGlassTokens.darkShadow : AuroraGlassTokens.lightShadow,
                radius: shadowRadius,
                x: 0,
                y: shadowRadius > 0 ? shadowRadius * 0.4 : 0
            )
    }
}

/// 品牌色玻璃主 CTA：玻璃打底 + 品牌渐变染色，比纯色实心更「浮」一层。
struct AuroraGlassGradientModifier: ViewModifier {

    var cornerRadius: CGFloat
    var enabled: Bool

    @Environment(\.colorScheme) private var scheme

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    }

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)

                    if enabled {
                        shape.fill(Aurora.brandGradient).opacity(scheme == .dark ? 0.78 : 0.88)
                    } else {
                        shape.fill(Aurora.muted.opacity(0.30))
                    }

                    shape.fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.26), Color.white.opacity(0.02)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }
            }
            .clipShape(shape)
            .overlay {
                shape.strokeBorder(Color.white.opacity(enabled ? 0.36 : 0.18), lineWidth: 1)
            }
            .shadow(
                color: Aurora.brand.opacity(enabled ? (scheme == .dark ? 0.35 : 0.26) : 0),
                radius: 14,
                x: 0,
                y: 7
            )
    }
}

// MARK: - 对外入口

extension View {

    /// 任意形状的玻璃底（形状必须是 InsettableShape，见 AuroraGlassModifier）
    func auroraGlass<S: InsettableShape>(
        _ shape: S,
        tint: Color? = nil,
        tintOpacity: Double = 0.16,
        shadowRadius: CGFloat = 10
    ) -> some View {
        modifier(
            AuroraGlassModifier(
                shape: shape,
                tint: tint,
                tintOpacity: tintOpacity,
                shadowRadius: shadowRadius
            )
        )
    }

    /// 圆角矩形（最常用）
    func auroraGlass(
        cornerRadius: CGFloat = 16,
        tint: Color? = nil,
        tintOpacity: Double = 0.16,
        shadowRadius: CGFloat = 10
    ) -> some View {
        auroraGlass(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous),
            tint: tint,
            tintOpacity: tintOpacity,
            shadowRadius: shadowRadius
        )
    }

    /// 胶囊（筛选 chip、状态标签）
    func auroraGlassCapsule(
        tint: Color? = nil,
        tintOpacity: Double = 0.16,
        shadowRadius: CGFloat = 4
    ) -> some View {
        auroraGlass(
            Capsule(style: .continuous),
            tint: tint,
            tintOpacity: tintOpacity,
            shadowRadius: shadowRadius
        )
    }

    /// 圆形（图标按钮）
    func auroraGlassCircle(
        diameter: CGFloat,
        tint: Color? = nil,
        tintOpacity: Double = 0.16
    ) -> some View {
        frame(width: diameter, height: diameter)
            .auroraGlass(Circle(), tint: tint, tintOpacity: tintOpacity, shadowRadius: 6)
    }

    /// 品牌渐变玻璃（主 CTA 专用）
    func auroraGlassGradient(cornerRadius: CGFloat = 16, enabled: Bool = true) -> some View {
        modifier(AuroraGlassGradientModifier(cornerRadius: cornerRadius, enabled: enabled))
    }
}

// MARK: - 玻璃上可读的文字色

/// 玻璃底是「模糊 + 半透明」，直接把品牌色铺上去在深色模式下会发灰。
/// 这几个函数按当前配色给一组对比度够用的文字色。
extension Aurora {

    static func readableBrand(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xA7A1FF) : Color(hex: 0x4338CA)
    }

    static func readableInk(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color.white.opacity(0.92) : Aurora.ink
    }

    static func readableSoft(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color.white.opacity(0.62) : Aurora.inkSoft
    }

    static func readableRose(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xFF97B4) : Color(hex: 0xB0224F)
    }

    static func readableWarning(_ scheme: ColorScheme) -> Color {
        scheme == .dark ? Color(hex: 0xFFC46B) : Color(hex: 0xA25E00)
    }
}
