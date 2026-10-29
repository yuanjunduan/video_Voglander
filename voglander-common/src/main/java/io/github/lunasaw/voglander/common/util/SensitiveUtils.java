package io.github.lunasaw.voglander.common.util;

/**
 * 敏感信息脱敏工具：用于日志与审计输出，避免密码、令牌等明文落盘。
 *
 * <p>只做展示层脱敏，不参与任何加密/解密逻辑。</p>
 *
 * @author luna
 */
public final class SensitiveUtils {

    /** 空值与极短值统一显示的占位符 */
    private static final String MASKED = "***";

    private SensitiveUtils() {
    }

    /**
     * 脱敏：保留首尾各 1 位，中间以 3 个星号代替。
     *
     * <p>长度不足 5 位时整体打码，避免仅凭首尾即还原真实值。</p>
     *
     * @param secret 原始敏感串
     * @return 脱敏后的串；入参为空返回空串
     */
    public static String mask(String secret) {
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        if (secret.length() < 5) {
            return MASKED;
        }
        return secret.charAt(0) + MASKED + secret.charAt(secret.length() - 1);
    }
}