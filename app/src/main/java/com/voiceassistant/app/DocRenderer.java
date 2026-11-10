package com.voiceassistant.app;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把帮助 / 版本说明这类 Markdown 文本渲染成文档页：
 * 标题按层级放大、列表统一悬挂缩进、行内代码用等宽字体、链接可点。
 * 目标是看起来像 GitHub 上的 README / Release 说明，而不是一整坨纯文本。
 */
final class DocRenderer {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final Pattern VERSION_HEADING = Pattern.compile("^v\\d+(\\.\\d+)*$");
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s)]+");

    private DocRenderer() { }

    static void render(Context context, LinearLayout container, String markdown) {
        String[] lines = markdown.replace("\r", "").split("\n", -1);
        StringBuilder paragraph = new StringBuilder();
        StringBuilder list = new StringBuilder();
        boolean firstVersion = true;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                flushParagraph(context, container, paragraph);
                flushList(context, container, list);
                continue;
            }
            if (line.startsWith("### ")) {
                flushParagraph(context, container, paragraph);
                flushList(context, container, list);
                addHeading(context, container, line.substring(4), 15, 14);
            } else if (line.startsWith("## ")) {
                flushParagraph(context, container, paragraph);
                flushList(context, container, list);
                String text = line.substring(3);
                if (VERSION_HEADING.matcher(text).matches()) {
                    addVersionHeading(context, container, text, firstVersion);
                    firstVersion = false;
                } else {
                    addHeading(context, container, text, 17, 20);
                }
            } else if (line.startsWith("# ")) {
                flushParagraph(context, container, paragraph);
                flushList(context, container, list);
                addHeading(context, container, line.substring(2), 22, 4);
            } else if (line.equals("---")) {
                flushParagraph(context, container, paragraph);
                flushList(context, container, list);
                addDivider(context, container);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                flushParagraph(context, container, paragraph);
                appendListItem(list, "•  ", line.substring(2));
            } else if (line.matches("^\\d+\\.\\s.*")) {
                flushParagraph(context, container, paragraph);
                int dot = line.indexOf('.');
                appendListItem(list, line.substring(0, dot + 1) + "  ", line.substring(dot + 1).trim());
            } else {
                flushList(context, container, list);
                if (paragraph.length() > 0) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flushParagraph(context, container, paragraph);
        flushList(context, container, list);
    }

    private static void appendListItem(StringBuilder list, String prefix, String text) {
        if (list.length() > 0) list.append('\n');
        list.append(prefix).append(text);
    }

    private static void flushParagraph(Context context, LinearLayout container, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        String text = paragraph.toString();
        paragraph.setLength(0);

        TextView view = newTextView(context, 14, 1.55f);
        view.setText(inline(context, text));
        applyLinks(view);
        addTopMargin(view, 8);
        container.addView(view);
    }

    private static void flushList(Context context, LinearLayout container, StringBuilder list) {
        if (list.length() == 0) return;
        String text = list.toString();
        list.setLength(0);

        TextView view = newTextView(context, 14, 1.55f);
        SpannableStringBuilder span = inline(context, text);
        // 悬挂缩进：项目符号顶格，折行对齐到文字
        span.setSpan(new LeadingMarginSpan.Standard(0, dp(context, 22)),
                0, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(span);
        applyLinks(view);
        addTopMargin(view, 6);
        container.addView(view);
    }

    private static void addHeading(Context context, LinearLayout container, String text,
                                   int sizeSp, int topMarginDp) {
        TextView view = newTextView(context, sizeSp, 1.35f);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setText(inline(context, text));
        applyLinks(view);
        addTopMargin(view, topMarginDp);
        container.addView(view);
    }

    /** 版本号单独做成一颗小标签，后面跟“最新”，类似 GitHub Release 的 tag。 */
    private static void addVersionHeading(Context context, LinearLayout container, String version,
                                          boolean latest) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 24);
        row.setLayoutParams(params);

        TextView tag = new TextView(context);
        tag.setText(version);
        tag.setTextSize(13);
        tag.setTypeface(Typeface.DEFAULT_BOLD);
        tag.setTextColor(ContextCompat.getColor(context, R.color.pillIdleText));
        tag.setBackgroundResource(R.drawable.bg_pill);
        int padH = dp(context, 10);
        int padV = dp(context, 4);
        tag.setPadding(padH, padV, padH, padV);
        row.addView(tag);

        if (latest) {
            TextView badge = new TextView(context);
            badge.setText("最新");
            badge.setTextSize(11);
            badge.setTextColor(ContextCompat.getColor(context, R.color.surface));
            badge.setBackgroundResource(R.drawable.bg_tag_accent);
            int bh = dp(context, 8);
            int bv = dp(context, 3);
            badge.setPadding(bh, bv, bh, bv);
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            badgeParams.leftMargin = dp(context, 8);
            badge.setLayoutParams(badgeParams);
            row.addView(badge);
        }
        container.addView(row);
    }

    private static void addDivider(Context context, LinearLayout container) {
        android.view.View divider = new android.view.View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        params.topMargin = dp(context, 16);
        params.bottomMargin = dp(context, 4);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(ContextCompat.getColor(context, R.color.cardStroke));
        container.addView(divider);
    }

    private static TextView newTextView(Context context, int sizeSp, float lineSpacingMultiplier) {
        TextView view = new TextView(context);
        view.setTextSize(sizeSp);
        view.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        view.setLineSpacing(0f, lineSpacingMultiplier);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private static void addTopMargin(android.view.View view, int topMarginDp) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(view.getContext(), topMarginDp);
        if (raw instanceof LinearLayout.LayoutParams) {
            params.width = raw.width;
            params.height = raw.height;
        }
        view.setLayoutParams(params);
    }

    private static void applyLinks(TextView view) {
        if (view.getText() instanceof Spanned) {
            URLSpan[] spans = ((Spanned) view.getText()).getSpans(0, view.getText().length(), URLSpan.class);
            if (spans.length > 0) {
                view.setMovementMethod(LinkMovementMethod.getInstance());
                view.setLinkTextColor(ContextCompat.getColor(view.getContext(), R.color.primary));
            }
        }
    }

    /** 行内样式：**粗体**、`代码`、[文字](链接)。 */
    private static SpannableStringBuilder inline(Context context, String raw) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int index = 0;
        while (index < raw.length()) {
            int boldAt = raw.indexOf("**", index);
            int codeAt = raw.indexOf('`', index);
            int linkAt = raw.indexOf('[', index);
            Matcher urlMatcher = BARE_URL.matcher(raw);
            int urlAt = -1;
            int urlEnd = -1;
            if (urlMatcher.find(index)) {
                urlAt = urlMatcher.start();
                urlEnd = urlMatcher.end();
            }

            int next = -1;
            char kind = 0;
            if (boldAt >= 0 && (next < 0 || boldAt < next)) { next = boldAt; kind = '*'; }
            if (codeAt >= 0 && (next < 0 || codeAt < next)) { next = codeAt; kind = '`'; }
            if (linkAt >= 0 && (next < 0 || linkAt < next)) { next = linkAt; kind = '['; }
            if (urlAt >= 0 && (next < 0 || urlAt < next)) { next = urlAt; kind = 'h'; }

            if (next < 0) {
                builder.append(raw.substring(index));
                break;
            }
            if (next > index) builder.append(raw.substring(index, next));

            if (kind == 'h') {
                int start = builder.length();
                String url = raw.substring(urlAt, urlEnd);
                builder.append(url);
                builder.setSpan(new URLSpan(url), start, builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(
                                ContextCompat.getColor(context, R.color.primary)),
                        start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = urlEnd;
            } else if (kind == '*') {
                int end = raw.indexOf("**", next + 2);
                if (end < 0) {
                    builder.append(raw.substring(next));
                    break;
                }
                int start = builder.length();
                builder.append(raw.substring(next + 2, end));
                builder.setSpan(new StyleSpan(Typeface.BOLD), start, builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = end + 2;
            } else if (kind == '`') {
                int end = raw.indexOf('`', next + 1);
                if (end < 0) {
                    builder.append(raw.substring(next));
                    break;
                }
                int start = builder.length();
                builder.append(raw.substring(next + 1, end));
                builder.setSpan(new TypefaceSpan("monospace"), start, builder.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new ForegroundColorSpan(
                                ContextCompat.getColor(context, R.color.primary)),
                        start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = end + 1;
            } else {
                Matcher matcher = LINK.matcher(raw);
                if (matcher.find(next) && matcher.start() == next) {
                    int start = builder.length();
                    builder.append(matcher.group(1));
                    builder.setSpan(new URLSpan(matcher.group(2)), start, builder.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new UnderlineSpan(), start, builder.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    builder.setSpan(new ForegroundColorSpan(
                                    ContextCompat.getColor(context, R.color.primary)),
                            start, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    index = matcher.end();
                } else {
                    builder.append('[');
                    index = next + 1;
                }
            }
        }
        return builder;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
