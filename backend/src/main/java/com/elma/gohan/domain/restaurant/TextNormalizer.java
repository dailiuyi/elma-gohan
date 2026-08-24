package com.elma.gohan.domain.restaurant;

import java.text.Normalizer;
import java.util.Locale;

/** 自由文本关键词归一化:trim + NFKC + 小写,供 dislike 匹配等场景使用。 */
public final class TextNormalizer {

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }
}
