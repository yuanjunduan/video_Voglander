package io.github.lunasaw.voglander.common.enums;

import lombok.Getter;

/**
 * 码流档次枚举（主码流 / 辅码流）。
 *
 * <p>
 * 与 {@code streamMode}（UDP / TCP_ACTIVE / TCP_PASSIVE，传输模式）正交：
 * 本枚举决定"拉哪一路码流"，streamMode 决定"怎么传"。
 * </p>
 *
 * <p>
 * <strong>编码进 streamId</strong>：{@code main} 不加后缀（存量流名/播放地址/会话全部不变），
 * {@code sub} 追加 {@code _sub}。streamId 是会话复用键与 ZLM 流名，只有把码流编码进去，
 * 主/辅两路才能各自独立建流、独立计数、独立释放。
 * </p>
 *
 * @author luna
 */
@Getter
public enum StreamProfileEnum {

    /**
     * 主码流（默认）
     */
    MAIN("main", "主码流"),

    /**
     * 辅码流（子码流）
     */
    SUB("sub", "辅码流"),

    ;

    /** streamId 码流后缀：仅辅码流加，主码流保持原流名兼容存量 */
    public static final String SUB_STREAM_SUFFIX = "_sub";

    private final String code;
    private final String desc;

    StreamProfileEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 宽容解析：空值/未知值一律按主码流处理（存量请求不带该字段，行为不变）。
     */
    public static StreamProfileEnum of(String code) {
        if (code == null || code.isBlank()) {
            return MAIN;
        }
        String normalized = code.trim().toLowerCase();
        return SUB.getCode().equals(normalized) ? SUB : MAIN;
    }

    /**
     * 是否辅码流。
     */
    public static boolean isSub(String code) {
        return of(code) == SUB;
    }

    /**
     * 取另一档码流（切换码流时用于定位"要对哪一路做替换"）。
     */
    public static StreamProfileEnum opposite(String code) {
        return of(code) == SUB ? MAIN : SUB;
    }

}
