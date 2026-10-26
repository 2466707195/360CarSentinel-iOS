import SwiftUI

// =============================================================================
// 合成画面（演示信号）
//
// 手机上还没连到车机时，宫格里不能是四个黑窟窿 —— 那样看起来像 App 坏了。
// 这里用 Canvas 画一段「合成车外画面」：地平线、透视车道、滚动虚线、扫描线。
// 它明确是**演示信号**（角标会写），但视觉上能撑住宫格，也验证了 16:9 裁切、
// 时间戳叠字、离线遮罩这些真实拉流时才暴露的排版问题。
// =============================================================================

struct SignalPlaceholder: View {

    var position: CameraPosition = .front
    var online: Bool = true
    /// 列表缩略图用紧凑版：少画几层，省性能
    var compact: Bool = false

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 12.0, paused: !online)) { context in
            let seconds = context.date.timeIntervalSinceReferenceDate
            Canvas { ctx, size in
                var canvas = ctx
                draw(&canvas, size: size, seconds: seconds)
            }
        }
        .background(Color(hex: 0x0B0B12))
    }

    private var accent: Color {
        switch position {
        case .front: return Aurora.brand
        case .rear: return Aurora.teal
        case .left: return Aurora.info
        case .right: return Aurora.warning
        case .cabin: return Aurora.rose
        }
    }

    private func draw(_ ctx: inout GraphicsContext, size: CGSize, seconds: TimeInterval) {
        let w = size.width
        let h = size.height

        guard online else {
            // 离线：纯静止的暗场 + 十字标，一眼看出没有信号
            ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Color(hex: 0x14141C)))
            let center = CGPoint(x: w / 2, y: h / 2)
            var cross = Path()
            cross.move(to: CGPoint(x: center.x - w * 0.05, y: center.y))
            cross.addLine(to: CGPoint(x: center.x + w * 0.05, y: center.y))
            cross.move(to: CGPoint(x: center.x, y: center.y - w * 0.05))
            cross.addLine(to: CGPoint(x: center.x, y: center.y + w * 0.05))
            ctx.stroke(cross, with: .color(Color(hex: 0x3A3A48)), lineWidth: 1)
            return
        }

        // 天空 → 地面
        let horizon = h * 0.42
        ctx.fill(
            Path(CGRect(x: 0, y: 0, width: w, height: horizon)),
            with: .linearGradient(
                Gradient(colors: [Color(hex: 0x141428), Color(hex: 0x1E1B3A)]),
                startPoint: .zero,
                endPoint: CGPoint(x: 0, y: horizon)
            )
        )
        ctx.fill(
            Path(CGRect(x: 0, y: horizon, width: w, height: h - horizon)),
            with: .linearGradient(
                Gradient(colors: [Color(hex: 0x16161F), Color(hex: 0x08080D)]),
                startPoint: CGPoint(x: 0, y: horizon),
                endPoint: CGPoint(x: 0, y: h)
            )
        )

        // 地平线辉光
        ctx.fill(
            Path(CGRect(x: 0, y: 0, width: w, height: h)),
            with: .radialGradient(
                Gradient(colors: [accent.opacity(0.34), .clear]),
                center: CGPoint(x: w * 0.5, y: horizon),
                startRadius: 0,
                endRadius: max(w * 0.62, 1)
            )
        )

        // 车道边线（透视）
        var edges = Path()
        edges.move(to: CGPoint(x: w * 0.5, y: horizon))
        edges.addLine(to: CGPoint(x: -w * 0.22, y: h))
        edges.move(to: CGPoint(x: w * 0.5, y: horizon))
        edges.addLine(to: CGPoint(x: w * 1.22, y: h))
        ctx.stroke(edges, with: .color(Color.white.opacity(0.16)), lineWidth: 1.4)

        // 中央虚线：随时间的相位向下滚
        if !compact {
            let phase = seconds.truncatingRemainder(dividingBy: 1.6) / 1.6
            for index in 0..<9 {
                let p = (Double(index) + phase) / 9.0
                let depth = pow(p, 2.4)
                let y = horizon + (h - horizon) * depth
                let halfWidth = w * 0.5 * (1 - depth) + w * 0.02 * depth
                let dashHeight = max(1.5, h * 0.055 * depth)
                let rect = CGRect(
                    x: w * 0.5 - max(1, halfWidth * 0.02),
                    y: y,
                    width: max(1.2, w * 0.02 * (0.25 + depth)),
                    height: dashHeight
                )
                ctx.fill(
                    Path(roundedRect: rect, cornerRadius: rect.width / 2),
                    with: .color(Color.white.opacity(0.14 + 0.42 * depth))
                )
            }
        }

        // 扫描线
        let scanPhase = seconds.truncatingRemainder(dividingBy: 3.4) / 3.4
        let scanY = h * scanPhase
        ctx.fill(
            Path(CGRect(x: 0, y: scanY - h * 0.045, width: w, height: h * 0.045)),
            with: .linearGradient(
                Gradient(colors: [.clear, accent.opacity(0.22)]),
                startPoint: CGPoint(x: 0, y: scanY - h * 0.045),
                endPoint: CGPoint(x: 0, y: scanY)
            )
        )

        // 四角暗角
        ctx.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .radialGradient(
                Gradient(colors: [.clear, Color.black.opacity(0.55)]),
                center: CGPoint(x: w * 0.5, y: h * 0.5),
                startRadius: min(w, h) * 0.32,
                endRadius: max(w, h) * 0.78
            )
        )
    }
}

// MARK: - 带叠字的摄像头画面容器

/// 宫格里的一格：画面 + 左上角机位标 + 右上角状态 + 右下角分辨率。
struct CameraCanvas: View {

    let camera: CameraSource
    var timestamp: String? = nil
    var showResolution: Bool = true
    var compact: Bool = false

    var body: some View {
        ZStack {
            SignalPlaceholder(position: camera.position, online: camera.online, compact: compact)

            VStack(spacing: 0) {
                HStack(alignment: .top, spacing: 4) {
                    label(camera.position.shortLabel, tint: Aurora.brand)
                    label(camera.name, tint: Aurora.brand)
                    Spacer(minLength: 4)
                    if camera.online {
                        RecDot(size: 5)
                        label("LIVE", tint: Aurora.rose)
                    } else {
                        label("离线", tint: Aurora.muted)
                    }
                }
                Spacer(minLength: 0)
                if let timestamp {
                    HStack {
                        Spacer(minLength: 0)
                        Text(timestamp)
                            .font(.system(size: 9.5, weight: .medium, design: .monospaced))
                            .foregroundStyle(Color.white.opacity(0.72))
                    }
                }
                if showResolution && !compact {
                    HStack {
                        Spacer(minLength: 0)
                        Text(camera.resolution)
                            .font(.system(size: 9, weight: .medium, design: .monospaced))
                            .foregroundStyle(Color.white.opacity(0.5))
                    }
                }
            }
            .padding(6)
        }
    }

    private func label(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.system(size: 9.5, weight: .semibold))
            .foregroundStyle(Color.white)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(Color.black.opacity(0.42))
            )
    }
}

// MARK: - REC 呼吸点

struct RecDot: View {

    var size: CGFloat = 6
    var color: Color = Aurora.rose

    @State private var pulsing = false

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .opacity(pulsing ? 0.35 : 1)
            .animation(
                .easeInOut(duration: 0.9).repeatForever(autoreverses: true),
                value: pulsing
            )
            .onAppear { pulsing = true }
    }
}
