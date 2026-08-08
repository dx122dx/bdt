package com.billy65536.infrastructure.security.core.internal;

import java.util.Objects;

import net.minecraft.util.Identifier;

/**
 * 带{@linkplain Origin 来源}的配置值，不可变。
 *
 * <p>安全框架内部用它替代裸值参与合并：值怎么覆盖，来源就怎么跟随，
 * 二者绑定在同一对象上，杜绝「值合并了但来源没跟上」的漂移。</p>
 *
 * <p>泛型化是为了扩展性——目前只用于 {@code String} 形态的锁定强制值，
 * 未来若出现数值 / 布尔形态的安全约束可直接复用同一合并语义。</p>
 *
 * @param <T> 被包装的值类型
 * @apiNote <b>框架内部类型，不属于公开 API，随时可能变更；下游模组不得引用。</b>
 *          唯一实现 {@code SimpleAttributedValue} 为包私有，只能经 {@link #of} 产出。
 */
public interface AttributedValue<T> {

    /**
     * 被包装的值。
     *
     * <p><b>允许为 {@code null}</b>：在配置锁定场景中，{@code null} 表示「仅锁定无强制值」，
     * 与空串「强制为空值」是严格区分的两态，包装后必须继续保持这一区分。</p>
     */
    T getValue();

    /** 来源标签，<b>永不为 {@code null}</b>（未知来源用 {@link Origin#UNKNOWN}）。 */
    Origin getOrigin();

    /**
     * 与更晚的值合并：值取 {@code later}，来源走 {@link Origin#merge(Origin)}。
     *
     * @param later 更晚参与合并的值；{@code null} 时返回 {@code this}
     * @return 合并后的值；与自身等价时可直接返回 {@code this}
     */
    AttributedValue<T> merge(AttributedValue<T> later);

    /**
     * 回填来源，值不变。
     *
     * <p>供框架在合并前为策略贡献的配置打上来源标签——这是「策略开发者零感知」的关键：
     * 策略只提供值，来源由框架统一注入。</p>
     *
     * <p>与 {@link #merge} 不同，本方法是<b>替换</b>而非累积：原有贡献者不予保留，
     * 因为回填发生在合并<i>之前</i>，此时值只可能出自 {@code policyId} 一家。</p>
     *
     * @param policyId 来源策略 id
     * @return 打上该来源的值；来源无变化时可直接返回 {@code this}
     */
    AttributedValue<T> withOrigin(Identifier policyId);

    /**
     * 创建带指定来源的值。
     *
     * @param <T>    被包装的值类型
     * @param value  被包装的值，允许为 {@code null}
     * @param origin 来源标签；{@code null} 时降级为 {@link Origin#UNKNOWN}
     * @return 不可变的带来源值
     */
    static <T> AttributedValue<T> of(T value, Origin origin) {
        return new SimpleAttributedValue<>(value, origin == null ? Origin.UNKNOWN : origin);
    }

    /**
     * 创建来源未知的值，等待框架经 {@link #withOrigin} 回填。
     *
     * @param <T>   被包装的值类型
     * @param value 被包装的值，允许为 {@code null}
     * @return 来源为 {@link Origin#UNKNOWN} 的不可变带来源值
     */
    static <T> AttributedValue<T> of(T value) {
        return new SimpleAttributedValue<>(value, Origin.UNKNOWN);
    }
}

/**
 * {@link AttributedValue} 的唯一实现。
 *
 * <p>刻意声明为<b>包私有</b>：外部（乃至框架其他包）都无法 {@code new}，
 * 只能经 {@link AttributedValue#of} 静态工厂产出，保证实现可自由演进。</p>
 *
 * @param <T> 被包装的值类型
 * @apiNote <b>框架内部类型，不属于公开 API，随时可能变更；下游模组不得引用。</b>
 */
final class SimpleAttributedValue<T> implements AttributedValue<T> {

    private final T value;
    private final Origin origin;

    SimpleAttributedValue(T value, Origin origin) {
        this.value = value;
        this.origin = origin;
    }

    @Override
    public T getValue() {
        return value;
    }

    @Override
    public Origin getOrigin() {
        return origin;
    }

    @Override
    public AttributedValue<T> merge(AttributedValue<T> later) {
        if (later == null) return this;
        Origin mergedOrigin = this.origin.merge(later.getOrigin());
        if (Objects.equals(this.value, later.getValue()) && mergedOrigin == this.origin) {
            return this;
        }
        return new SimpleAttributedValue<>(later.getValue(), mergedOrigin);
    }

    @Override
    public AttributedValue<T> withOrigin(Identifier policyId) {
        Origin replaced = Origin.of(policyId);
        if (replaced.equals(this.origin)) return this;
        return new SimpleAttributedValue<>(this.value, replaced);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SimpleAttributedValue<?> other)) return false;
        return Objects.equals(value, other.value) && origin.equals(other.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, origin);
    }

    @Override
    public String toString() {
        return "AttributedValue[" + value + " <- " + origin + "]";
    }
}
