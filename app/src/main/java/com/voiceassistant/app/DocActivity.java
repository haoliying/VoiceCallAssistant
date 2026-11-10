package com.voiceassistant.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;

/**
 * 帮助 / 版本说明的独立页面：顶部返回栏 + 按 Markdown 渲染的正文。
 * 排版模仿 GitHub 的 README 与 Release 页面。
 */
public class DocActivity extends AppCompatActivity {

    public static final String EXTRA_DOC = "doc";
    public static final String DOC_HELP = "help";
    public static final String DOC_CHANGELOG = "changelog";

    private static final String REPO_URL = "https://github.com/haoliying/VoiceCallAssistant";
    private static final String RELEASES_URL = REPO_URL + "/releases";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc);

        String doc = getIntent().getStringExtra(EXTRA_DOC);
        boolean help = DOC_HELP.equals(doc);

        MaterialToolbar toolbar = findViewById(R.id.docToolbar);
        toolbar.setTitle(help ? getString(R.string.help_title) : getString(R.string.changelog_title));
        toolbar.setNavigationOnClickListener(v -> finish());

        LinearLayout container = findViewById(R.id.docContainer);
        DocRenderer.render(this, container, getString(help ? R.string.help_text : R.string.changelog_text));

        LinearLayout links = findViewById(R.id.docLinks);
        addLinkRow(links, getString(R.string.doc_repo), REPO_URL);
        addLinkRow(links, getString(R.string.doc_releases), RELEASES_URL);

        TextView version = new TextView(this);
        version.setText("VoiceCallAssistant · v" + BuildConfig.VERSION_NAME);
        version.setTextSize(11);
        version.setTextColor(ContextCompat.getColor(this, R.color.textTertiary));
        version.setPadding(0, dp(18), 0, 0);
        links.addView(version);
    }

    private void addLinkRow(LinearLayout parent, String title, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackgroundResource(R.drawable.bg_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> openUrl(url));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(ContextCompat.getColor(this, R.color.primary));
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(ContextCompat.getColor(this, R.color.textTertiary));
        row.addView(arrow);

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
