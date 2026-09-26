package com.corrodinggames.rts.gameFramework.ui;

import com.corrodinggames.rts.game.units.custom.logicBooleans.VariableScope;
import com.corrodinggames.rts.gameFramework.GameEngine;
import com.corrodinggames.rts.gameFramework.Utility;
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine;
import com.corrodinggames.rts.gameFramework.graphics.Texture;
import io.github.rwx.geometry.Rect;
import io.github.rwx.geometry.RectF;
import io.github.rwx.render.canvas.KoolPaint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/* JADX INFO: renamed from: com.corrodinggames.rts.gameFramework.f.d */
/* JADX INFO: loaded from: game-lib.jar:com/corrodinggames/rts/gameFramework/f/d.class */
public class TextUtils {

    /* JADX INFO: renamed from: a */
    static Rect tempRect = new Rect();

    /* JADX INFO: renamed from: b */
    static ArrayList lines = new ArrayList();

    /* JADX INFO: renamed from: c */
    static final RectF backgroundRect = new RectF();

    /* JADX INFO: renamed from: d */
    static final RectF textBounds = new RectF();

    /* JADX INFO: renamed from: a */
    public static int getLineHeight(KoolPaint paint) {
        return GameEngine.getInstance().renderGraphicsEngine.a("abcABC123!|", paint) + 4;
    }

    /* JADX INFO: renamed from: b */
    public static int getCharWidth(KoolPaint paint) {
        int iA = GameEngine.getInstance().renderGraphicsEngine.a("abcABC123!|", paint);
        if (GameEngine.isGDXVersion) {
            return iA + 2;
        }
        return iA;
    }

    private static final int WRAP_CACHE_LIMIT = 256;

    private static final LinkedHashMap<WrapCacheKey, ArrayList<String>> wrapCache =
            new LinkedHashMap<WrapCacheKey, ArrayList<String>>(WRAP_CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<WrapCacheKey, ArrayList<String>> eldest) {
                    return size() > WRAP_CACHE_LIMIT;
                }
            };

    private static final class WrapCacheKey {
        final String text;
        final int paintSize;
        final int maxWidth;

        WrapCacheKey(String text, int paintSize, int maxWidth) {
            this.text = text;
            this.paintSize = paintSize;
            this.maxWidth = maxWidth;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof WrapCacheKey)) {
                return false;
            }
            WrapCacheKey key = (WrapCacheKey) other;
            return paintSize == key.paintSize && maxWidth == key.maxWidth && text.equals(key.text);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * text.hashCode() + paintSize) + maxWidth;
        }
    }

    public static ArrayList<String> wrapLines(String text, KoolPaint paint, float maxWidth) {
        ArrayList<String> wrapped = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            wrapped.add("");
            return wrapped;
        }
        int paintSize = paint == null ? 0 : Float.floatToIntBits(paint.k());
        WrapCacheKey cacheKey = new WrapCacheKey(text, paintSize, (int) maxWidth);
        ArrayList<String> cached = wrapCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        GraphicsEngine graphics = GameEngine.getInstance().renderGraphicsEngine;
        for (String paragraph : Utility.splitByChar(text, '\n')) {
            if (paragraph.isEmpty()) {
                wrapped.add("");
                continue;
            }
            int start = 0;
            while (start < paragraph.length()) {
                int end = start;
                int fitted = start;
                while (end < paragraph.length()) {
                    int next = paragraph.offsetByCodePoints(end, 1);
                    if (fitted > start && graphics.b(paragraph.substring(start, next), paint) > maxWidth) {
                        break;
                    }
                    fitted = next;
                    end = next;
                    if (graphics.b(paragraph.substring(start, fitted), paint) > maxWidth) {
                        break;
                    }
                }
                if (fitted >= paragraph.length()) {
                    wrapped.add(paragraph.substring(start));
                    break;
                }
                int breakAt = paragraph.substring(start, fitted).lastIndexOf(' ');
                if (breakAt > 0) {
                    fitted = start + breakAt;
                }
                if (fitted <= start) {
                    fitted = paragraph.offsetByCodePoints(start, 1);
                }
                wrapped.add(paragraph.substring(start, fitted).trim());
                start = fitted;
                while (start < paragraph.length() && paragraph.charAt(start) == ' ') {
                    start++;
                }
            }
        }
        wrapCache.put(cacheKey, wrapped);
        return wrapped;
    }

    /* JADX INFO: renamed from: a */
    public static ArrayList wrapText(String str, Rect rect, KoolPaint paint, KoolPaint paint2, boolean z) {
        int iLastIndexOf;
        lines.clear();
        String str2 = VariableScope.nullOrMissingString;
        int size = 0;
        int i = 0;
        while (true) {
            int i2 = i;
            if (i2 >= str.length()) {
                break;
            }
            int iA = paint2.a((CharSequence) str, i2, str.length(), true, rect.b() - 5, (float[]) null);
            if (iA == 0) {
                break;
            }
            int iIndexOf = str.indexOf("\n", i2 + 1);
            if (iIndexOf != -1 && iIndexOf < i2 + iA) {
                iA = iIndexOf - i2;
            } else if (i2 + iA < str.length() && (iLastIndexOf = str.substring(i2, i2 + iA).lastIndexOf(" ")) != -1 && iLastIndexOf != 0) {
                iA = iLastIndexOf;
            }
            String strReplaceAll = str.substring(i2, i2 + iA).replaceAll("(\\n)", VariableScope.nullOrMissingString);
            if (strReplaceAll.length() > str2.length()) {
                str2 = strReplaceAll;
                size = lines.size();
            }
            lines.add(strReplaceAll);
            i = i2 + iA;
        }
        rect.d = rect.b + (lines.size() * getLineHeight(paint2));
        if (z) {
            float fD = rect.d();
            KoolPaint paint3 = paint2;
            if (size == 0) {
                paint3 = paint;
            }
            float fB = GameEngine.getInstance().renderGraphicsEngine.b(str2, paint3);
            if (fB < rect.b()) {
                rect.a = (int) (fD - (fB / 2.0f));
                rect.c = (int) (fD + (fB / 2.0f));
            }
        }
        return lines;
    }

    /* JADX INFO: renamed from: a */
    public static void drawTextWithBackground(String str, float f, float f2, KoolPaint paint, KoolPaint paint2, float f3, float f4, float f5, float f6) {
        GraphicsEngine graphicsEngine = GameEngine.getInstance().renderGraphicsEngine;
        float fB = graphicsEngine.b(str, paint);
        textBounds.a(f, f2, f + fB, f2 + graphicsEngine.a(str, paint));
        backgroundRect.a(textBounds);
        if (paint.j() == KoolPaint.Align.CENTER) {
            backgroundRect.a(-(fB / 2.0f), 0.0f);
        }
        backgroundRect.a -= f3;
        backgroundRect.b -= f4;
        backgroundRect.c += f5;
        backgroundRect.d += f6;
        graphicsEngine.a(backgroundRect, paint2);
        graphicsEngine.a(str, textBounds.a, textBounds.d, paint);
    }

    /* JADX INFO: renamed from: a */
    public static float getScale(Texture texture, float f, float f2) {
        return getScaleWithBounds(texture, f, f2, f, f2);
    }

    /* JADX INFO: renamed from: a */
    public static float getScaleWithBounds(Texture texture, float f, float f2, float f3, float f4) {
        float f5 = texture.p;
        float f6 = texture.q;
        float f7 = 1.0f;
        if (f5 * 1.0f < f) {
            float f8 = f / f5;
            if (f8 > 1.0f) {
                f7 = f8;
            }
        }
        if (f6 * f7 < f2) {
            float f9 = f2 / f6;
            if (f9 > f7) {
                f7 = f9;
            }
        }
        if (f5 * f7 > f3) {
            float f10 = f3 / f5;
            if (f10 < f7) {
                f7 = f10;
            }
        }
        if (f6 * f7 > f4) {
            float f11 = f4 / f6;
            if (f11 < f7) {
                f7 = f11;
            }
        }
        return f7;
    }
}
