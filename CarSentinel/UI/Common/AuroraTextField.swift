import SwiftUI
import UIKit

/// Aurora UI 输入框：玻璃底、14pt 圆角、聚焦时品牌色描边。
///
/// 对应 Material 的 `OutlinedTextField`。底改成玻璃是为了跟按钮/底栏同一套语言；
/// 聚焦时用品牌色描边盖掉玻璃的白色内圈，光标落点才明确。
struct AuroraTextField: View {

    let placeholder: String
    @Binding var text: String
    var systemIcon: String? = nil
    var trailingSystemIcon: String? = nil
    var isSecure: Bool = false
    var keyboardType: UIKeyboardType = .default
    var onTrailingTap: (() -> Void)? = nil

    @FocusState var isFocused: Bool
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: 8) {
            if let systemIcon {
                Image(systemName: systemIcon)
                    .font(.system(size: 15))
                    .foregroundStyle(Aurora.readableSoft(scheme))
            }

            ZStack(alignment: .leading) {
                if text.isEmpty {
                    Text(placeholder)
                        .font(AuroraFont.bodyMedium)
                        .foregroundStyle(Aurora.muted)
                        .allowsHitTesting(false)
                }
                field
            }

            if let trailingSystemIcon {
                Button {
                    onTrailingTap?()
                } label: {
                    Image(systemName: trailingSystemIcon)
                        .font(.system(size: 15))
                        .foregroundStyle(Aurora.readableSoft(scheme))
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity)
        .auroraGlass(AuroraShape.control, tint: Aurora.brand, tintOpacity: isFocused ? 0.06 : 0.0, shadowRadius: 0)
        .overlay(
            AuroraShape.control.stroke(
                isFocused ? Aurora.brand.opacity(0.85) : Aurora.line.opacity(scheme == .dark ? 0.3 : 0.55),
                lineWidth: isFocused ? 1.4 : 1
            )
        )
        .animation(.easeOut(duration: 0.15), value: isFocused)
    }

    // `.focused` 必须挂在输入控件本身上。挂在外面那层 ZStack 上不会生效，
    // 表现为「点了输入框但描边不变蓝」——这是 SwiftUI 的老坑。
    @ViewBuilder
    private var field: some View {
        if isSecure {
            SecureField("", text: $text)
                .font(AuroraFont.bodyMedium)
                .foregroundStyle(Aurora.readableInk(scheme))
                .focused($isFocused)
        } else {
            TextField("", text: $text)
                .font(AuroraFont.bodyMedium)
                .foregroundStyle(Aurora.readableInk(scheme))
                .keyboardType(keyboardType)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($isFocused)
        }
    }
}
